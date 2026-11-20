package com.plm.jobs.domain

import com.plm.jobs.api.JobCancelledException
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobFinished
import com.plm.jobs.api.JobPermanentFailure
import com.plm.jobs.api.JobStatus
import com.plm.jobs.infra.ClaimedJob
import com.plm.jobs.infra.JobRepository
import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val log = LoggerFactory.getLogger(JobWorker::class.java)

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class JobScheduling

/**
 * Runs queued jobs (roadmap 3.1).
 *
 * - **Claiming**: every poll, due jobs are claimed with `SKIP LOCKED` (several instances can run) and
 *   leased: the worker renews the lease while the job runs. A job whose lease runs out (its worker
 *   crashed or was restarted) is queued again, or failed after its last attempt.
 * - **Running**: handlers run on a pool of [concurrency] threads, outside any transaction, as the
 *   job's actor. Progress is written as it comes and tells the handler whether to stop.
 * - **Ending**: success stores the result; a failure is retried after `backoff × 2^(attempt-1)`
 *   (at most an hour) until its last attempt; each end publishes [JobFinished].
 */
@Component
internal class JobWorker(
    private val jobs: JobRepository,
    private val handlers: JobHandlers,
    private val events: DomainEvents,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @param:Value("\${plm.jobs.enabled:true}") private val enabled: Boolean,
    @param:Value("\${plm.jobs.concurrency:4}") private val concurrency: Int,
    @param:Value("\${plm.jobs.lease:60s}") private val lease: Duration,
    @param:Value("\${plm.jobs.retry-backoff:30s}") private val backoff: Duration,
) : DisposableBean {
    private val worker = "${ManagementFactory.getRuntimeMXBean().name}#${UUID.randomUUID().toString().take(8)}"
    private val pool: ExecutorService =
        Executors.newFixedThreadPool(concurrency.coerceAtLeast(1)) { r ->
            Thread(r, "plm-job-${threads.incrementAndGet()}").apply { isDaemon = true }
        }
    private val threads = AtomicInteger()
    private val busy = AtomicInteger()

    /** Running here: job id → when its lease was last renewed. */
    private val running = ConcurrentHashMap<UUID, Instant>()

    @Scheduled(fixedDelayString = "\${plm.jobs.poll-interval:1s}", initialDelayString = "\${plm.jobs.initial-delay:3s}")
    fun poll() {
        if (!enabled) return
        try {
            renewLeases()
            recover()
            val free = concurrency - busy.get()
            for (job in claim(free)) {
                busy.incrementAndGet()
                pool.submit {
                    try {
                        execute(job)
                    } finally {
                        busy.decrementAndGet()
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Job poll failed: {}", e.toString())
        }
    }

    /** Runs what's due now, in this thread; returns how many ran. For tests and tools. */
    fun runDue(): Int {
        recover()
        var ran = 0
        while (true) {
            val batch = claim(10)
            if (batch.isEmpty()) return ran
            batch.forEach { execute(it) }
            ran += batch.size
        }
    }

    private fun claim(limit: Int): List<ClaimedJob> {
        if (limit <= 0) return emptyList()
        val now = clock.instant()
        return tx.execute { jobs.claim(now, now.plus(lease), worker, limit) }
    }

    private fun renewLeases() {
        val now = clock.instant()
        for ((id, renewed) in running) {
            if (Duration.between(renewed, now) >= lease.dividedBy(3)) {
                jobs.extendLease(id, now.plus(lease))
                running[id] = now
            }
        }
    }

    /** Jobs whose worker stopped: queued again, or failed after their last attempt. */
    fun recover() {
        val now = clock.instant()
        tx.executeWithoutResult {
            for (c in jobs.expired(now)) {
                if (running.containsKey(c.job.id)) continue
                val error = "Its worker stopped while running it (attempt ${c.job.attempts})"
                if (c.job.attempts < c.job.maxAttempts) {
                    jobs.requeue(c.job.id, now, error)
                    jobs.appendLog(c.job.id, now, "WARN", "$error; queued again")
                } else {
                    end(c, JobStatus.FAILED, null, error)
                }
            }
        }
    }

    fun execute(claimed: ClaimedJob) {
        val job = claimed.job
        val handler = handlers.byType[job.type]
        if (handler == null) {
            tx.executeWithoutResult {
                end(
                    claimed,
                    JobStatus.FAILED,
                    null,
                    "No handler for ${job.type} on this server",
                )
            }
            return
        }
        val actor = Actor(claimed.tenant, PrincipalId(job.submittedBy), siteAdmin = claimed.runAsAdmin)
        running[job.id] = clock.instant()
        val context = Context(job.id, actor, job.payload, job.attempts, job.cancelRequested)
        jobs.appendLog(job.id, clock.instant(), "INFO", "Started, attempt ${job.attempts} of ${job.maxAttempts}")
        try {
            val result = handler.run(context)
            tx.executeWithoutResult { end(claimed, JobStatus.SUCCEEDED, result, null) }
        } catch (e: JobCancelledException) {
            tx.executeWithoutResult { end(claimed, JobStatus.CANCELLED, null, null) }
        } catch (e: JobPermanentFailure) {
            tx.executeWithoutResult { end(claimed, JobStatus.FAILED, null, e.message) }
        } catch (e: Exception) {
            val error = e.message ?: e.javaClass.simpleName
            log.info("Job {} ({}) failed on attempt {}: {}", job.id, job.type, job.attempts, e.toString())
            tx.executeWithoutResult {
                if (job.attempts < job.maxAttempts) {
                    val wait =
                        backoff
                            .multipliedBy(
                                1L shl (job.attempts - 1).coerceIn(0, 16),
                            ).coerceAtMost(Duration.ofHours(1))
                    val now = clock.instant()
                    jobs.requeue(job.id, now.plus(wait), error)
                    jobs.appendLog(job.id, now, "WARN", "Failed: $error. Trying again in ${wait.seconds} s")
                } else {
                    end(claimed, JobStatus.FAILED, null, error)
                }
            }
        } finally {
            running.remove(job.id)
        }
    }

    /** Within a transaction: the end state, a log line and the event. */
    private fun end(
        claimed: ClaimedJob,
        status: JobStatus,
        result: Map<String, Any?>?,
        error: String?,
    ) {
        val job = claimed.job
        val now = clock.instant()
        jobs.finish(job.id, status, result, error, now)
        jobs.appendLog(
            job.id,
            now,
            if (status == JobStatus.FAILED) "ERROR" else "INFO",
            when (status) {
                JobStatus.SUCCEEDED -> "Succeeded"
                JobStatus.CANCELLED -> "Cancelled"
                else -> "Failed: $error"
            },
        )
        val actor = Actor(claimed.tenant, PrincipalId(job.submittedBy), siteAdmin = claimed.runAsAdmin)
        events.publish(actor, JobFinished(job.id, job.type, job.title, status, job.submittedBy, error))
    }

    private inner class Context(
        override val jobId: UUID,
        override val actor: Actor,
        override val payload: Map<String, Any?>,
        override val attempt: Int,
        cancelled: Boolean,
    ) : JobContext {
        @Volatile private var cancel = cancelled

        override val cancelRequested: Boolean
            get() {
                if (!cancel) cancel = jobs.extendLease(jobId, clock.instant().plus(lease))
                return cancel
            }

        override fun progress(
            done: Int,
            total: Int?,
            message: String?,
        ) {
            val now = clock.instant()
            if (jobs.progress(jobId, done, total, message, now.plus(lease))) cancel = true
            running[jobId] = now
        }

        override fun log(
            message: String,
            level: String,
        ) = jobs.appendLog(
            jobId,
            clock.instant(),
            level.uppercase().takeIf { it in setOf("INFO", "WARN", "ERROR") } ?: "INFO",
            message,
        )
    }

    override fun destroy() {
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
    }
}

/** Fires due schedules: each queues a job and moves to its next run. */
@Component
internal class JobScheduler(
    private val jobs: JobRepository,
    private val handlers: JobHandlers,
    private val service: DefaultJobService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @param:Value("\${plm.jobs.enabled:true}") private val enabled: Boolean,
) {
    @Scheduled(
        fixedDelayString = "\${plm.jobs.schedule-interval:15s}",
        initialDelayString = "\${plm.jobs.initial-delay:3s}",
    )
    fun tick() {
        if (enabled) {
            try {
                fireDue()
            } catch (e: Exception) {
                log.warn("Job schedules failed: {}", e.toString())
            }
        }
    }

    fun fireDue(): Int =
        tx.execute {
            val now = clock.instant()
            val due = jobs.dueSchedules(now)
            for (d in due) {
                val s = d.schedule
                val next =
                    runCatching { Cron.next(s.cron, s.timeZone, now) }.getOrNull()
                val handler = handlers.byType[s.type]
                if (handler == null) {
                    log.warn("Schedule {} names job type {}, which this server doesn't have", s.name, s.type)
                    jobs.scheduleRan(s.id, s.lastJobId ?: UUID(0, 0), now, next)
                    continue
                }
                val id = service.enqueue(d.tenant, handler, s.payload, s.name, s.runAs, d.runAsAdmin, s.id, null)
                jobs.scheduleRan(s.id, id, now, next)
            }
            due.size
        }
}
