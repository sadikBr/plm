package com.plm.jobs.infra

import com.plm.jobs.api.Job
import com.plm.jobs.api.JobLogEntry
import com.plm.jobs.api.JobProgress
import com.plm.jobs.api.JobSchedule
import com.plm.jobs.api.JobStatus
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A job claimed by a worker, with what it needs to run it. */
internal data class ClaimedJob(
    val job: Job,
    val tenant: TenantId,
    val runAsAdmin: Boolean,
)

internal data class NewJob(
    val id: UUID,
    val tenant: TenantId,
    val type: String,
    val title: String,
    val payload: Map<String, Any?>,
    val submittedBy: String,
    val runAsAdmin: Boolean,
    val scheduleId: UUID?,
    val maxAttempts: Int,
    val runAt: Instant,
    val now: Instant,
)

internal data class DueSchedule(
    val tenant: TenantId,
    val schedule: JobSchedule,
    val runAsAdmin: Boolean,
)

@Repository
internal class JobRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    private fun ts(i: Instant?) = i?.let { Timestamp.from(it) }

    // ---------- Jobs ----------

    fun insert(j: NewJob) {
        jdbc
            .sql(
                """
                insert into job (id, tenant_id, type, title, payload, status, submitted_by, run_as_admin, schedule_id,
                                 max_attempts, run_at, created_at)
                values (:id, :tenant, :type, :title, cast(:payload as jsonb), 'QUEUED', :by, :admin, :schedule,
                        :max, :runAt, :now)
                """.trimIndent(),
            ).param("id", j.id)
            .param("tenant", j.tenant.value)
            .param("type", j.type)
            .param("title", j.title)
            .param("payload", json.writeValueAsString(j.payload))
            .param("by", j.submittedBy)
            .param("admin", j.runAsAdmin)
            .param("schedule", j.scheduleId)
            .param("max", j.maxAttempts)
            .param("runAt", ts(j.runAt))
            .param("now", ts(j.now))
            .update()
    }

    fun find(
        tenant: TenantId,
        id: UUID,
    ): Job? =
        jdbc
            .sql("select * from job where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun list(
        tenant: TenantId,
        status: JobStatus?,
        type: String?,
        submittedBy: String?,
        limit: Int,
    ): List<Job> =
        jdbc
            .sql(
                """
                select * from job where tenant_id = :tenant
                  and (cast(:status as text) is null or status = :status)
                  and (cast(:type as text) is null or type = :type)
                  and (cast(:by as text) is null or submitted_by = :by)
                order by seq desc limit :limit
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("status", status?.name)
            .param("type", type)
            .param("by", submittedBy)
            .param("limit", limit)
            .query { rs, _ -> map(rs) }
            .list()

    fun stats(tenant: TenantId): Map<JobStatus, Int> =
        jdbc
            .sql("select status, count(*) as n from job where tenant_id = :tenant group by status")
            .param("tenant", tenant.value)
            .query { rs, _ -> JobStatus.valueOf(rs.getString("status")) to rs.getInt("n") }
            .list()
            .toMap()

    fun log(id: UUID): List<JobLogEntry> =
        jdbc
            .sql("select * from job_log where job_id = :id order by seq")
            .param("id", id)
            .query {
                rs,
                _,
                ->
                JobLogEntry(rs.getTimestamp("at").toInstant(), rs.getString("level"), rs.getString("message"))
            }.list()

    fun appendLog(
        id: UUID,
        at: Instant,
        level: String,
        message: String,
    ) {
        jdbc
            .sql("insert into job_log (job_id, at, level, message) values (:id, :at, :level, :message)")
            .param("id", id)
            .param("at", ts(at))
            .param("level", level)
            .param("message", message.take(4000))
            .update()
    }

    /**
     * Claims up to [limit] due jobs: RUNNING, leased until [lease], one more attempt. Other workers
     * skip rows being claimed (`SKIP LOCKED`).
     */
    fun claim(
        now: Instant,
        lease: Instant,
        worker: String,
        limit: Int,
    ): List<ClaimedJob> {
        if (limit <= 0) return emptyList()
        return jdbc
            .sql(
                """
                update job set status = 'RUNNING', lease_until = :lease, worker = :worker, attempts = attempts + 1,
                    started_at = coalesce(started_at, :now)
                where id in (
                    select id from job where status = 'QUEUED' and run_at <= :now
                    order by run_at, seq limit :limit
                    for update skip locked)
                returning *
                """.trimIndent(),
            ).param("now", ts(now))
            .param("lease", ts(lease))
            .param("worker", worker)
            .param("limit", limit)
            .query { rs, _ ->
                ClaimedJob(
                    map(rs),
                    TenantId(rs.getObject("tenant_id", UUID::class.java)),
                    rs.getBoolean("run_as_admin"),
                )
            }.list()
    }

    /** Progress, which also extends the lease. Returns whether cancelling was asked for. */
    fun progress(
        id: UUID,
        done: Int,
        total: Int?,
        message: String?,
        lease: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                update job set progress_done = :done, progress_total = :total, progress_message = :message,
                    lease_until = :lease
                where id = :id and status = 'RUNNING'
                returning cancel_requested
                """.trimIndent(),
            ).param("id", id)
            .param("done", done)
            .param("total", total)
            .param("message", message?.take(500))
            .param("lease", ts(lease))
            .query(Boolean::class.java)
            .optional()
            .orElse(false)

    fun extendLease(
        id: UUID,
        lease: Instant,
    ): Boolean =
        jdbc
            .sql("update job set lease_until = :lease where id = :id and status = 'RUNNING' returning cancel_requested")
            .param("id", id)
            .param("lease", ts(lease))
            .query(Boolean::class.java)
            .optional()
            .orElse(false)

    fun finish(
        id: UUID,
        status: JobStatus,
        result: Map<String, Any?>?,
        error: String?,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                update job set status = :status, result = cast(:result as jsonb), error = :error, finished_at = :now,
                    lease_until = null
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .param("status", status.name)
            .param("result", result?.let { json.writeValueAsString(it) })
            .param("error", error?.take(2000))
            .param("now", ts(now))
            .update()
    }

    /** Back in the queue, to try again at [runAt]. */
    fun requeue(
        id: UUID,
        runAt: Instant,
        error: String?,
    ) {
        jdbc
            .sql(
                "update job set status = 'QUEUED', run_at = :runAt, lease_until = null, worker = null, error = :error where id = :id",
            ).param("id", id)
            .param("runAt", ts(runAt))
            .param("error", error?.take(2000))
            .update()
    }

    /** Running jobs whose worker stopped renewing its lease (crashed, restarted). */
    fun expired(now: Instant): List<ClaimedJob> =
        jdbc
            .sql("select * from job where status = 'RUNNING' and lease_until < :now for update skip locked")
            .param("now", ts(now))
            .query { rs, _ ->
                ClaimedJob(
                    map(rs),
                    TenantId(rs.getObject("tenant_id", UUID::class.java)),
                    rs.getBoolean("run_as_admin"),
                )
            }.list()

    fun requestCancel(id: UUID) {
        jdbc.sql("update job set cancel_requested = true where id = :id").param("id", id).update()
    }

    /** From the start again: progress, result and attempts cleared; the log is kept. */
    fun reset(
        id: UUID,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                update job set status = 'QUEUED', attempts = 0, run_at = :now, cancel_requested = false,
                    progress_done = null, progress_total = null, progress_message = null, result = null, error = null,
                    started_at = null, finished_at = null, lease_until = null, worker = null
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .param("now", ts(now))
            .update()
    }

    // ---------- Schedules ----------

    private val scheduleSql =
        """
        select s.*, j.status as last_status from job_schedule s left join job j on j.id = s.last_job_id
        """.trimIndent()

    fun schedules(tenant: TenantId): List<JobSchedule> =
        jdbc
            .sql("$scheduleSql where s.tenant_id = :tenant order by lower(s.name)")
            .param("tenant", tenant.value)
            .query { rs, _ -> mapSchedule(rs) }
            .list()

    fun schedule(
        tenant: TenantId,
        id: UUID,
    ): JobSchedule? = schedules(tenant).firstOrNull { it.id == id }

    fun scheduleNameTaken(
        tenant: TenantId,
        name: String,
        except: UUID?,
    ): Boolean =
        jdbc
            .sql(
                "select count(*) from job_schedule where tenant_id = :tenant and lower(name) = lower(:name) and id <> :except",
            ).param("tenant", tenant.value)
            .param("name", name)
            .param("except", except ?: UUID(0, 0))
            .query(Int::class.java)
            .single() > 0

    fun saveSchedule(
        tenant: TenantId,
        s: JobSchedule,
        runAsAdmin: Boolean,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into job_schedule (tenant_id, id, name, type, payload, cron, time_zone, enabled, run_as, run_as_admin,
                                          next_run_at, created_at, created_by, updated_at, updated_by)
                values (:tenant, :id, :name, :type, cast(:payload as jsonb), :cron, :tz, :enabled, :runAs, :admin,
                        :next, :now, :by, :now, :by)
                on conflict (id) do update set name = excluded.name, type = excluded.type, payload = excluded.payload,
                    cron = excluded.cron, time_zone = excluded.time_zone, enabled = excluded.enabled,
                    run_as = excluded.run_as, run_as_admin = excluded.run_as_admin, next_run_at = excluded.next_run_at,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", s.id)
            .param("name", s.name)
            .param("type", s.type)
            .param("payload", json.writeValueAsString(s.payload))
            .param("cron", s.cron)
            .param("tz", s.timeZone)
            .param("enabled", s.enabled)
            .param("runAs", s.runAs)
            .param("admin", runAsAdmin)
            .param("next", ts(s.nextRunAt))
            .param("now", ts(now))
            .param("by", s.updatedBy)
            .update()
    }

    fun deleteSchedule(
        tenant: TenantId,
        id: UUID,
    ) {
        jdbc
            .sql("delete from job_schedule where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .update()
    }

    /** Due schedules, locked for this transaction (other instances skip them). */
    fun dueSchedules(now: Instant): List<DueSchedule> =
        jdbc
            .sql(
                """
                select s.*, null as last_status from job_schedule s
                where s.enabled and s.next_run_at <= :now
                order by s.next_run_at limit 50
                for update skip locked
                """.trimIndent(),
            ).param("now", ts(now))
            .query { rs, _ ->
                DueSchedule(
                    TenantId(rs.getObject("tenant_id", UUID::class.java)),
                    mapSchedule(rs),
                    rs.getBoolean("run_as_admin"),
                )
            }.list()

    fun scheduleRan(
        id: UUID,
        jobId: UUID,
        ranAt: Instant,
        next: Instant?,
    ) {
        jdbc
            .sql(
                "update job_schedule set last_run_at = :ran, last_job_id = :job, next_run_at = coalesce(:next, next_run_at) where id = :id",
            ).param("id", id)
            .param("job", jobId)
            .param("ran", ts(ranAt))
            .param("next", ts(next))
            .update()
    }

    // ---------- Mapping ----------

    @Suppress("UNCHECKED_CAST")
    private fun obj(text: String?): Map<String, Any?>? =
        text?.let { json.readValue(it, Map::class.java) as Map<String, Any?> }

    private fun map(rs: ResultSet): Job {
        val done = rs.getObject("progress_done") as Int?
        return Job(
            id = rs.getObject("id", UUID::class.java),
            type = rs.getString("type"),
            title = rs.getString("title"),
            status = JobStatus.valueOf(rs.getString("status")),
            payload = obj(rs.getString("payload")).orEmpty(),
            submittedBy = rs.getString("submitted_by"),
            scheduleId = rs.getObject("schedule_id", UUID::class.java),
            progress =
                done?.let {
                    JobProgress(
                        it,
                        rs.getObject("progress_total") as Int?,
                        rs.getString("progress_message"),
                    )
                },
            result = obj(rs.getString("result")),
            error = rs.getString("error"),
            attempts = rs.getInt("attempts"),
            maxAttempts = rs.getInt("max_attempts"),
            cancelRequested = rs.getBoolean("cancel_requested"),
            runAt = rs.getTimestamp("run_at").toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            startedAt = rs.getTimestamp("started_at")?.toInstant(),
            finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
        )
    }

    private fun mapSchedule(rs: ResultSet) =
        JobSchedule(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            type = rs.getString("type"),
            payload = obj(rs.getString("payload")).orEmpty(),
            cron = rs.getString("cron"),
            timeZone = rs.getString("time_zone"),
            enabled = rs.getBoolean("enabled"),
            runAs = rs.getString("run_as"),
            nextRunAt = rs.getTimestamp("next_run_at")?.toInstant(),
            lastRunAt = rs.getTimestamp("last_run_at")?.toInstant(),
            lastJobId = rs.getObject("last_job_id", UUID::class.java),
            lastJobStatus = rs.getString("last_status")?.let { JobStatus.valueOf(it) },
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            updatedBy = rs.getString("updated_by"),
        )
}
