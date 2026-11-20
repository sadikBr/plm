package com.plm.jobs.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

enum class JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class JobProgress(
    val done: Int,
    /** Null when the job can't tell how much there is. */
    val total: Int?,
    val message: String?,
)

data class Job(
    val id: UUID,
    val type: String,
    val title: String,
    val status: JobStatus,
    val payload: Map<String, Any?>,
    val submittedBy: String,
    val scheduleId: UUID?,
    val progress: JobProgress?,
    val result: Map<String, Any?>?,
    val error: String?,
    val attempts: Int,
    val maxAttempts: Int,
    val cancelRequested: Boolean,
    val runAt: Instant,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

data class JobLogEntry(
    val at: Instant,
    /** INFO, WARN or ERROR */
    val level: String,
    val message: String,
)

/** What a running job can do: report progress, write to its log, notice it was cancelled. */
interface JobContext {
    val jobId: UUID

    /** Who it runs as: the person who submitted it, or the schedule's owner. */
    val actor: Actor
    val payload: Map<String, Any?>

    /** 1 on the first try. */
    val attempt: Int

    fun progress(
        done: Int,
        total: Int? = null,
        message: String? = null,
    )

    fun log(
        message: String,
        level: String = "INFO",
    )

    /** True once someone asked to cancel; long jobs check it between steps. */
    val cancelRequested: Boolean

    /** Throws [JobCancelledException] if cancelling was asked for. */
    fun checkCancelled() {
        if (cancelRequested) throw JobCancelledException()
    }
}

/** Thrown from a handler to stop: the job ends CANCELLED, not FAILED. */
class JobCancelledException : RuntimeException("Cancelled")

/** Thrown from a handler for a failure that retrying won't fix: the job fails at once. */
class JobPermanentFailure(
    message: String,
) : RuntimeException(message)

/**
 * A kind of job. Modules declare handlers as beans; the worker runs them outside any transaction
 * (a handler opens its own where it needs them). Whatever [run] returns is the job's result.
 */
interface JobHandler {
    /** `<module>.<name>`, e.g. `identity.purge-tokens`. */
    val type: String
    val label: String
    val description: String get() = ""

    /** Only site administrators may submit or schedule it. */
    val adminOnly: Boolean get() = true
    val maxAttempts: Int get() = 3

    /** Throw a validation error for a payload it can't run; called when submitted or scheduled. */
    fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) {}

    /** A title for a job of this kind, from its payload. */
    fun title(payload: Map<String, Any?>): String = label

    fun run(context: JobContext): Map<String, Any?>
}

data class JobType(
    val type: String,
    val label: String,
    val description: String,
    val adminOnly: Boolean,
)

data class SubmitJob(
    val type: String,
    val payload: Map<String, Any?> = emptyMap(),
    /** Defaults to the handler's. */
    val title: String? = null,
    /** Later rather than now. */
    val runAt: Instant? = null,
)

data class JobQuery(
    val status: JobStatus? = null,
    val type: String? = null,
    /** Only the actor's own (administrators see everyone's otherwise). */
    val mine: Boolean = false,
    val limit: Int = 50,
)

/**
 * Background jobs (roadmap 3.1). Anyone can follow their own jobs; site administrators see and
 * manage all of them. Error codes: `JOB_NOT_FOUND`, `UNKNOWN_JOB_TYPE`, `ADMIN_REQUIRED`,
 * `JOB_NOT_CANCELLABLE`, `JOB_NOT_RETRYABLE`.
 */
interface JobService {
    fun types(actor: Actor): List<JobType>

    fun submit(
        actor: Actor,
        job: SubmitJob,
    ): Job

    fun get(
        actor: Actor,
        id: UUID,
    ): Job

    fun list(
        actor: Actor,
        query: JobQuery = JobQuery(),
    ): List<Job>

    fun log(
        actor: Actor,
        id: UUID,
    ): List<JobLogEntry>

    /** A queued job is cancelled at once; a running one when it next checks. */
    fun cancel(
        actor: Actor,
        id: UUID,
    ): Job

    /** Runs a failed or cancelled job again, from the start. */
    fun retry(
        actor: Actor,
        id: UUID,
    ): Job

    /** Counts by status, for the monitor. */
    fun stats(actor: Actor): Map<JobStatus, Int>
}

data class JobSchedule(
    val id: UUID,
    val name: String,
    val type: String,
    val payload: Map<String, Any?>,
    val cron: String,
    val timeZone: String,
    val enabled: Boolean,
    val runAs: String,
    val nextRunAt: Instant?,
    val lastRunAt: Instant?,
    val lastJobId: UUID?,
    val lastJobStatus: JobStatus?,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class ScheduleSettings(
    val name: String,
    val type: String,
    val cron: String,
    val payload: Map<String, Any?> = emptyMap(),
    val timeZone: String = "UTC",
    val enabled: Boolean = true,
)

/**
 * Recurring jobs: a job type, its payload and a cron expression. Site administrators only. A
 * scheduled job runs as whoever last saved the schedule. Error codes: `SCHEDULE_NOT_FOUND`,
 * `INVALID_SCHEDULE`, `DUPLICATE_SCHEDULE`.
 */
interface JobScheduleService {
    fun list(actor: Actor): List<JobSchedule>

    fun create(
        actor: Actor,
        settings: ScheduleSettings,
    ): JobSchedule

    fun update(
        actor: Actor,
        id: UUID,
        settings: ScheduleSettings,
    ): JobSchedule

    fun delete(
        actor: Actor,
        id: UUID,
    )

    /** Queues a job now, without changing the next planned run. */
    fun runNow(
        actor: Actor,
        id: UUID,
    ): Job
}

/** A job ended: succeeded, failed (after its last try) or cancelled. */
data class JobFinished(
    val jobId: UUID,
    val type: String,
    val title: String,
    val status: JobStatus,
    val submittedBy: String,
    val error: String?,
) : DomainEvent {
    override val eventType = "jobs.JobFinished.v1"
    override val aggregateType = "job"
    override val aggregateId: UUID = jobId
}

data class JobScheduleChanged(
    val scheduleId: UUID,
    val name: String,
    /** CREATED, UPDATED, DELETED */
    val change: String,
) : DomainEvent {
    override val eventType = "jobs.JobScheduleChanged.v1"
    override val aggregateType = "job-schedule"
    override val aggregateId: UUID = scheduleId
}
