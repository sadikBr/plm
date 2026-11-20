package com.plm.jobs

import com.plm.jobs.api.JobQuery
import com.plm.jobs.api.JobScheduleService
import com.plm.jobs.api.JobService
import com.plm.jobs.api.JobStatus
import com.plm.jobs.api.ScheduleSettings
import com.plm.jobs.api.SubmitJob
import com.plm.jobs.domain.JobScheduler
import com.plm.jobs.domain.JobWorker
import com.plm.jobs.infra.JobRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import plm.testing.MutableClock
import plm.testing.PlmIntegrationTest
import java.time.Duration
import java.util.UUID
import kotlin.concurrent.thread

/** The job queue and scheduler (roadmap 3.1), driven by hand: the background worker is off. */
@PlmIntegrationTest
@TestPropertySource(properties = ["plm.jobs.enabled=false"])
internal class JobsIT(
    @Autowired private val jobs: JobService,
    @Autowired private val schedules: JobScheduleService,
    @Autowired private val worker: JobWorker,
    @Autowired private val scheduler: JobScheduler,
    @Autowired private val repository: JobRepository,
    @Autowired private val clock: MutableClock,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun code(call: () -> Unit) = (runCatching(call).exceptionOrNull() as PlmException).code

    private fun test(
        steps: Int = 3,
        failTimes: Int = 0,
        stepMillis: Int = 0,
    ) = jobs.submit(
        admin,
        SubmitJob(
            "system.test",
            mapOf(
                "steps" to steps,
                "failTimes" to failTimes,
                "stepMillis" to stepMillis,
            ),
        ),
    )

    /** Nothing left queued for the next test. */
    @AfterEach
    fun drain() {
        jobs.list(admin, JobQuery(status = JobStatus.QUEUED, limit = 500)).forEach { jobs.cancel(admin, it.id) }
        schedules.list(admin).filter { it.name.startsWith("IT ") }.forEach { schedules.delete(admin, it.id) }
    }

    @Test
    fun `a job runs, reports progress and keeps its result and log`() {
        val job = test(steps = 4)
        assertThat(job.status).isEqualTo(JobStatus.QUEUED)
        assertThat(job.title).isEqualTo("Test job: 4 steps")

        assertThat(worker.runDue()).isEqualTo(1)
        val done = jobs.get(admin, job.id)
        assertThat(done.status).isEqualTo(JobStatus.SUCCEEDED)
        assertThat(done.progress!!.done).isEqualTo(4)
        assertThat(done.progress.total).isEqualTo(4)
        assertThat(done.result).isEqualTo(mapOf("steps" to 4))
        assertThat(done.attempts).isEqualTo(1)
        assertThat(jobs.log(admin, job.id).map { it.message })
            .containsSubsequence("Queued", "Started, attempt 1 of 3", "All 4 steps done", "Succeeded")
    }

    @Test
    fun `a failure is retried after a wait, and fails for good after the last attempt`() {
        val flaky = test(failTimes = 1)
        worker.runDue()
        val waiting = jobs.get(admin, flaky.id)
        assertThat(waiting.status).isEqualTo(JobStatus.QUEUED)
        assertThat(waiting.error).contains("Failing on purpose")
        assertThat(waiting.runAt).isEqualTo(clock.instant().plusSeconds(30))
        assertThat(worker.runDue()).isZero()

        clock.advance(Duration.ofSeconds(31))
        worker.runDue()
        assertThat(jobs.get(admin, flaky.id).status).isEqualTo(JobStatus.SUCCEEDED)
        assertThat(jobs.get(admin, flaky.id).attempts).isEqualTo(2)

        val broken = test(failTimes = 9)
        repeat(3) {
            worker.runDue()
            clock.advance(Duration.ofMinutes(5))
        }
        val failed = jobs.get(admin, broken.id)
        assertThat(failed.status).isEqualTo(JobStatus.FAILED)
        assertThat(failed.attempts).isEqualTo(3)

        // Run again from the start.
        val again = jobs.retry(admin, broken.id)
        assertThat(again.status).isEqualTo(JobStatus.QUEUED)
        assertThat(again.attempts).isZero()
        assertThat(code { jobs.retry(admin, flaky.id) }).isEqualTo("JOB_NOT_RETRYABLE")
    }

    @Test
    fun `cancelling stops a queued job at once and a running one at its next step`() {
        val queued = test()
        assertThat(jobs.cancel(admin, queued.id).status).isEqualTo(JobStatus.CANCELLED)
        assertThat(code { jobs.cancel(admin, queued.id) }).isEqualTo("JOB_NOT_CANCELLABLE")

        val long = test(steps = 200, stepMillis = 20)
        val runner = thread { worker.runDue() }
        var tries = 0
        while (jobs.get(admin, long.id).status != JobStatus.RUNNING && tries++ < 100) Thread.sleep(20)
        jobs.cancel(admin, long.id)
        runner.join(10_000)
        val stopped = jobs.get(admin, long.id)
        assertThat(stopped.status).isEqualTo(JobStatus.CANCELLED)
        assertThat(stopped.progress!!.done).isLessThan(200)
    }

    @Test
    fun `a job whose worker stopped is queued again when its lease runs out`() {
        val job = test()
        repository.claim(clock.instant(), clock.instant().plusSeconds(60), "gone", 10)
        assertThat(jobs.get(admin, job.id).status).isEqualTo(JobStatus.RUNNING)

        clock.advance(Duration.ofSeconds(61))
        worker.recover()
        val back = jobs.get(admin, job.id)
        assertThat(back.status).isEqualTo(JobStatus.QUEUED)
        assertThat(back.error).contains("worker stopped")
        worker.runDue()
        assertThat(jobs.get(admin, job.id).status).isEqualTo(JobStatus.SUCCEEDED)
    }

    @Test
    fun `people see their own jobs only, and only administrators start admin jobs`() {
        val job = test()
        assertThat(code { jobs.submit(alice, SubmitJob("system.test")) }).isEqualTo("ADMIN_REQUIRED")
        assertThat(code { jobs.submit(admin, SubmitJob("nope.nothing")) }).isEqualTo("UNKNOWN_JOB_TYPE")
        assertThat(code { jobs.submit(admin, SubmitJob("system.test", mapOf("steps" to 0))) }).isEqualTo("INVALID_JOB")
        assertThat(jobs.list(alice)).noneMatch { it.id == job.id }
        assertThat(code { jobs.get(alice, job.id) }).isEqualTo("JOB_NOT_FOUND")
        assertThat(jobs.types(alice)).noneMatch { it.type == "system.test" }
        assertThat(jobs.stats(admin)[JobStatus.QUEUED]).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `a schedule queues its job when due, then moves to its next run`() {
        val name = "IT nightly ${UUID.randomUUID()}"
        assertThat(
            code {
                schedules.create(admin, ScheduleSettings(name, "system.test", "every day"))
            },
        ).isEqualTo("INVALID_SCHEDULE")
        assertThat(
            code {
                schedules.create(
                    admin,
                    ScheduleSettings(name, "system.test", "0 2 * * *", timeZone = "Mars/Base"),
                )
            },
        ).isEqualTo("INVALID_SCHEDULE")

        val s = schedules.create(admin, ScheduleSettings(name, "system.test", "*/5 * * * *", mapOf("steps" to 2)))
        val first = s.nextRunAt!!
        assertThat(first).isAfter(clock.instant())
        assertThat(
            code {
                schedules.create(alice, ScheduleSettings("x", "system.test", "0 2 * * *"))
            },
        ).isEqualTo("ADMIN_REQUIRED")

        clock.set(first.plusSeconds(1))
        assertThat(scheduler.fireDue()).isGreaterThanOrEqualTo(1)
        val fired = schedules.list(admin).single { it.id == s.id }
        assertThat(fired.nextRunAt).isEqualTo(first.plus(Duration.ofMinutes(5)))
        val job = jobs.get(admin, fired.lastJobId!!)
        assertThat(job.title).isEqualTo(name)
        assertThat(job.scheduleId).isEqualTo(s.id)
        worker.runDue()
        assertThat(schedules.list(admin).single { it.id == s.id }.lastJobStatus).isEqualTo(JobStatus.SUCCEEDED)

        val manual = schedules.runNow(admin, s.id)
        assertThat(manual.status).isEqualTo(JobStatus.QUEUED)
        assertThat(
            schedules.list(admin).single { it.id == s.id }.nextRunAt,
        ).isEqualTo(first.plus(Duration.ofMinutes(5)))
    }
}
