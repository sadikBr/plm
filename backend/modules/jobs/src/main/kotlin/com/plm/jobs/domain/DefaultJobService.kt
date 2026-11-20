package com.plm.jobs.domain

import com.plm.jobs.api.Job
import com.plm.jobs.api.JobFinished
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobLogEntry
import com.plm.jobs.api.JobQuery
import com.plm.jobs.api.JobSchedule
import com.plm.jobs.api.JobScheduleChanged
import com.plm.jobs.api.JobScheduleService
import com.plm.jobs.api.JobService
import com.plm.jobs.api.JobStatus
import com.plm.jobs.api.JobType
import com.plm.jobs.api.ScheduleSettings
import com.plm.jobs.api.SubmitJob
import com.plm.jobs.infra.JobRepository
import com.plm.jobs.infra.NewJob
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** The job types this instance can run, by type. */
@Service
internal class JobHandlers(
    handlers: List<JobHandler>,
) {
    val byType: Map<String, JobHandler> =
        handlers.associateBy { it.type }.also {
            require(it.size == handlers.size) { "Two job handlers share a type: ${handlers.map { h -> h.type }}" }
        }

    fun require(type: String): JobHandler =
        byType[type]
            ?: throw ValidationException("UNKNOWN_JOB_TYPE", "There's no job type $type", mapOf("type" to type))
}

@Service
@Transactional
internal class DefaultJobService(
    private val jobs: JobRepository,
    private val handlers: JobHandlers,
    private val events: DomainEvents,
    private val clock: Clock,
) : JobService {
    @Transactional(readOnly = true)
    override fun types(actor: Actor): List<JobType> =
        handlers.byType.values
            .filter { !it.adminOnly || actor.siteAdmin }
            .sortedBy { it.label }
            .map { JobType(it.type, it.label, it.description, it.adminOnly) }

    override fun submit(
        actor: Actor,
        job: SubmitJob,
    ): Job {
        val handler = handlers.require(job.type)
        if (handler.adminOnly && !actor.siteAdmin) {
            throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators can start ${handler.label} jobs")
        }
        handler.validate(actor, job.payload)
        val id =
            enqueue(
                actor.tenant,
                handler,
                job.payload,
                job.title,
                actor.principal.value,
                actor.siteAdmin,
                null,
                job.runAt,
            )
        return jobs.find(actor.tenant, id)!!
    }

    /** Queues a job without checking who may: for schedules and other modules' own checks. */
    fun enqueue(
        tenant: TenantId,
        handler: JobHandler,
        payload: Map<String, Any?>,
        title: String?,
        runAs: String,
        runAsAdmin: Boolean,
        scheduleId: UUID?,
        runAt: Instant?,
    ): UUID {
        val now = clock.instant()
        val id = UuidV7.generate(clock)
        jobs.insert(
            NewJob(
                id = id,
                tenant = tenant,
                type = handler.type,
                title = title?.trim()?.ifEmpty { null } ?: handler.title(payload),
                payload = payload,
                submittedBy = runAs,
                runAsAdmin = runAsAdmin,
                scheduleId = scheduleId,
                maxAttempts = handler.maxAttempts.coerceIn(1, 20),
                runAt = runAt?.takeIf { it.isAfter(now) } ?: now,
                now = now,
            ),
        )
        jobs.appendLog(id, now, "INFO", if (scheduleId != null) "Queued by its schedule" else "Queued")
        return id
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: UUID,
    ): Job = find(actor, id)

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        query: JobQuery,
    ): List<Job> =
        jobs.list(
            actor.tenant,
            query.status,
            query.type,
            if (query.mine || !actor.siteAdmin) actor.principal.value else null,
            query.limit.coerceIn(1, 500),
        )

    @Transactional(readOnly = true)
    override fun log(
        actor: Actor,
        id: UUID,
    ): List<JobLogEntry> = jobs.log(find(actor, id).id)

    override fun cancel(
        actor: Actor,
        id: UUID,
    ): Job {
        val job = find(actor, id)
        val now = clock.instant()
        when (job.status) {
            JobStatus.QUEUED -> {
                jobs.finish(id, JobStatus.CANCELLED, null, null, now)
                jobs.appendLog(id, now, "INFO", "Cancelled before it started")
                events.publish(actor, JobFinished(id, job.type, job.title, JobStatus.CANCELLED, job.submittedBy, null))
            }
            JobStatus.RUNNING -> {
                jobs.requestCancel(id)
                jobs.appendLog(id, now, "INFO", "Cancelling: asked to stop")
            }
            else -> throw ConflictException(
                "JOB_NOT_CANCELLABLE",
                "The job has already ended (${job.status.name.lowercase()})",
            )
        }
        return jobs.find(actor.tenant, id)!!
    }

    override fun retry(
        actor: Actor,
        id: UUID,
    ): Job {
        val job = find(actor, id)
        if (job.status != JobStatus.FAILED && job.status != JobStatus.CANCELLED) {
            throw ConflictException("JOB_NOT_RETRYABLE", "Only failed or cancelled jobs can run again")
        }
        val now = clock.instant()
        jobs.reset(id, now)
        jobs.appendLog(id, now, "INFO", "Queued again")
        return jobs.find(actor.tenant, id)!!
    }

    @Transactional(readOnly = true)
    override fun stats(actor: Actor): Map<JobStatus, Int> {
        requireAdmin(actor)
        val counts = jobs.stats(actor.tenant)
        return JobStatus.entries.associateWith { counts[it] ?: 0 }
    }

    /** Someone else's job is as good as missing, unless you're an administrator. */
    private fun find(
        actor: Actor,
        id: UUID,
    ): Job {
        val job = jobs.find(actor.tenant, id)
        if (job == null || (!actor.siteAdmin && job.submittedBy != actor.principal.value)) {
            throw NotFoundException("JOB_NOT_FOUND", "There's no such job", mapOf("jobId" to id))
        }
        return job
    }
}

@Service
@Transactional
internal class DefaultJobScheduleService(
    private val jobs: JobRepository,
    private val handlers: JobHandlers,
    private val service: DefaultJobService,
    private val events: DomainEvents,
    private val clock: Clock,
) : JobScheduleService {
    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<JobSchedule> {
        requireAdmin(actor)
        return jobs.schedules(actor.tenant)
    }

    override fun create(
        actor: Actor,
        settings: ScheduleSettings,
    ): JobSchedule = save(actor, null, settings)

    override fun update(
        actor: Actor,
        id: UUID,
        settings: ScheduleSettings,
    ): JobSchedule = save(actor, id, settings)

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        val existing = find(actor, id)
        jobs.deleteSchedule(actor.tenant, id)
        events.publish(actor, JobScheduleChanged(id, existing.name, "DELETED"))
    }

    override fun runNow(
        actor: Actor,
        id: UUID,
    ): com.plm.jobs.api.Job {
        requireAdmin(actor)
        val s = find(actor, id)
        val handler = handlers.require(s.type)
        val jobId = service.enqueue(actor.tenant, handler, s.payload, s.name, actor.principal.value, true, s.id, null)
        jobs.scheduleRan(s.id, jobId, clock.instant(), null)
        return service.get(actor, jobId)
    }

    private fun save(
        actor: Actor,
        id: UUID?,
        settings: ScheduleSettings,
    ): JobSchedule {
        requireAdmin(actor)
        val existing = id?.let { find(actor, it) }
        val name = settings.name.trim()
        if (name.isEmpty() ||
            name.length > 100
        ) {
            throw ValidationException("INVALID_SCHEDULE", "A schedule needs a name")
        }
        if (jobs.scheduleNameTaken(actor.tenant, name, id)) {
            throw ConflictException("DUPLICATE_SCHEDULE", "There's already a schedule named $name")
        }
        val handler = handlers.require(settings.type)
        handler.validate(actor, settings.payload)
        val now = clock.instant()
        val next =
            try {
                Cron.next(settings.cron, settings.timeZone, now)
            } catch (e: IllegalArgumentException) {
                throw ValidationException("INVALID_SCHEDULE", e.message ?: "Invalid cron expression")
            }
        val schedule =
            JobSchedule(
                id = id ?: UuidV7.generate(clock),
                name = name,
                type = handler.type,
                payload = settings.payload,
                cron = settings.cron.trim(),
                timeZone = settings.timeZone,
                enabled = settings.enabled,
                runAs = actor.principal.value,
                nextRunAt = next,
                lastRunAt = existing?.lastRunAt,
                lastJobId = existing?.lastJobId,
                lastJobStatus = existing?.lastJobStatus,
                updatedAt = now,
                updatedBy = actor.principal.value,
            )
        jobs.saveSchedule(actor.tenant, schedule, actor.siteAdmin, now)
        events.publish(actor, JobScheduleChanged(schedule.id, name, if (id == null) "CREATED" else "UPDATED"))
        return find(actor, schedule.id)
    }

    private fun find(
        actor: Actor,
        id: UUID,
    ) = jobs.schedule(actor.tenant, id)
        ?: throw NotFoundException("SCHEDULE_NOT_FOUND", "There's no such schedule", mapOf("scheduleId" to id))
}

internal fun requireAdmin(actor: Actor) {
    if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators manage jobs")
}
