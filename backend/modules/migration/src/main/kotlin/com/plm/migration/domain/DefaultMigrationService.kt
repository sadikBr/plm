package com.plm.migration.domain

import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.jobs.api.Job
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import com.plm.jobs.api.JobService
import com.plm.jobs.api.SubmitJob
import com.plm.migration.api.Migration
import com.plm.migration.api.MigrationItem
import com.plm.migration.api.MigrationMappings
import com.plm.migration.api.MigrationService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile

internal data class MigrationRow(
    val id: UUID,
    val name: String,
    val contextId: UUID,
    val fileName: String,
    val size: Long,
    val mappings: MigrationMappings,
    val lastJobId: UUID?,
    val createdAt: Instant,
    val createdBy: String,
)

@Component
internal class MigrationRepository(
    private val jdbc: JdbcClient,
) {
    private val columns =
        "id, name, context_id, file_name, length(package) as size, mappings::text as mappings, " +
            "last_job_id, created_at, created_by"

    private fun row(rs: java.sql.ResultSet) =
        MigrationRow(
            rs.getObject("id", UUID::class.java),
            rs.getString("name"),
            rs.getObject("context_id", UUID::class.java),
            rs.getString("file_name"),
            rs.getLong("size"),
            Packages.mappings(rs.getString("mappings")),
            rs.getObject("last_job_id", UUID::class.java),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("created_by"),
        )

    fun list(tenant: UUID): List<MigrationRow> =
        jdbc
            .sql("select $columns from migration where tenant_id = :tenant order by created_at desc")
            .param("tenant", tenant)
            .query { rs, _ -> row(rs) }
            .list()

    fun find(
        tenant: UUID,
        id: UUID,
    ): MigrationRow? =
        jdbc
            .sql("select $columns from migration where tenant_id = :tenant and id = :id")
            .param("tenant", tenant)
            .param("id", id)
            .query { rs, _ -> row(rs) }
            .optional()
            .orElse(null)

    fun bytes(id: UUID): ByteArray =
        jdbc
            .sql("select package from migration where id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getBytes(1) }
            .single()

    fun insert(
        tenant: UUID,
        row: MigrationRow,
        bytes: ByteArray,
    ) {
        jdbc
            .sql(
                """
                insert into migration (id, tenant_id, name, context_id, file_name, package, mappings, created_at, created_by)
                values (:id, :tenant, :name, :context, :file, :package, cast(:mappings as jsonb), :at, :by)
                """,
            ).param("id", row.id)
            .param("tenant", tenant)
            .param("name", row.name)
            .param("context", row.contextId)
            .param("file", row.fileName)
            .param("package", bytes)
            .param("mappings", Packages.write(row.mappings))
            .param("at", Timestamp.from(row.createdAt))
            .param("by", row.createdBy)
            .update()
    }

    fun setMappings(
        id: UUID,
        mappings: MigrationMappings,
    ) {
        jdbc
            .sql("update migration set mappings = cast(:m as jsonb) where id = :id")
            .param("id", id)
            .param("m", Packages.write(mappings))
            .update()
    }

    fun setJob(
        id: UUID,
        job: UUID,
    ) {
        jdbc
            .sql("update migration set last_job_id = :job where id = :id")
            .param("id", id)
            .param("job", job)
            .update()
    }

    fun delete(id: UUID) {
        jdbc.sql("delete from migration where id = :id").param("id", id).update()
    }

    fun items(id: UUID): List<MigrationItem> =
        jdbc
            .sql("select * from migration_item where migration_id = :id order by updated_at, legacy_id")
            .param("id", id)
            .query { rs, _ ->
                MigrationItem(
                    rs.getString("legacy_id"),
                    rs.getString("number"),
                    rs.getObject("master_id", UUID::class.java),
                    if (rs.getString("status") == "DONE" &&
                        !rs.getBoolean("links_done")
                    ) {
                        "LINKS_PENDING"
                    } else {
                        rs.getString("status")
                    },
                    rs.getString("message"),
                )
            }.list()

    /** Legacy ids brought in, with their objects here. */
    fun done(id: UUID): Map<String, UUID> =
        jdbc
            .sql("select legacy_id, master_id from migration_item where migration_id = :id and status = 'DONE'")
            .param("id", id)
            .query { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }
            .list()
            .toMap()

    fun updated(id: UUID): Set<String> =
        jdbc
            .sql("select legacy_id from migration_item where migration_id = :id and updated")
            .param("id", id)
            .query { rs, _ -> rs.getString(1) }
            .list()
            .toSet()

    fun linksPending(id: UUID): Set<String> =
        jdbc
            .sql("select legacy_id from migration_item where migration_id = :id and status = 'DONE' and not links_done")
            .param("id", id)
            .query { rs, _ -> rs.getString(1) }
            .list()
            .toSet()

    fun counts(id: UUID): Pair<Int, Int> =
        jdbc
            .sql(
                "select count(*) filter (where status = 'DONE'), count(*) filter (where status = 'FAILED') " +
                    "from migration_item where migration_id = :id",
            ).param("id", id)
            .query { rs, _ -> rs.getInt(1) to rs.getInt(2) }
            .single()

    fun record(
        id: UUID,
        legacyId: String,
        number: String,
        masterId: UUID?,
        ok: Boolean,
        message: String,
        at: Instant,
        updated: Boolean = false,
    ) {
        jdbc
            .sql(
                """
                insert into migration_item (migration_id, legacy_id, number, master_id, status, message, updated_at, updated)
                values (:id, :legacy, :number, :master, :status, :message, :at, :updated)
                on conflict (migration_id, legacy_id) do update
                    set master_id = excluded.master_id, status = excluded.status, message = excluded.message,
                        updated_at = excluded.updated_at, links_done = false, updated = excluded.updated
                """,
            ).param("id", id)
            .param("updated", updated)
            .param("legacy", legacyId)
            .param("number", number)
            .param("master", masterId)
            .param("status", if (ok) "DONE" else "FAILED")
            .param("message", message)
            .param("at", Timestamp.from(at))
            .update()
    }

    fun linksDone(
        id: UUID,
        legacyId: String,
        ok: Boolean,
        message: String,
        at: Instant,
    ) {
        jdbc
            .sql(
                """
                update migration_item set links_done = :ok, updated_at = :at,
                    message = case when :message = '' then message else :message end
                where migration_id = :id and legacy_id = :legacy
                """,
            ).param("id", id)
            .param("legacy", legacyId)
            .param("ok", ok)
            .param("message", message)
            .param("at", Timestamp.from(at))
            .update()
    }
}

@Service
internal class DefaultMigrationService(
    private val repository: MigrationRepository,
    private val engine: MigrationEngine,
    private val contexts: ContextService,
    private val jobs: JobService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : MigrationService {
    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators run migrations")
    }

    private fun row(
        actor: Actor,
        id: UUID,
    ) = repository.find(actor.tenant.value, id)
        ?: throw NotFoundException("MIGRATION_NOT_FOUND", "Migration $id does not exist", mapOf("id" to id))

    private fun view(
        actor: Actor,
        r: MigrationRow,
    ): Migration {
        val contents = Packages.read(repository.bytes(r.id))
        val (done, failed) = repository.counts(r.id)
        return Migration(
            r.id,
            r.name,
            r.contextId,
            contexts.get(actor, ContextId(r.contextId)).name,
            r.fileName,
            r.size,
            r.mappings,
            engine.analyse(actor, contents, r.mappings, repository.done(r.id).keys),
            done,
            failed,
            r.lastJobId,
            r.createdAt,
            r.createdBy,
        )
    }

    override fun list(actor: Actor): List<Migration> {
        requireAdmin(actor)
        return tx.execute { repository.list(actor.tenant.value).map { view(actor, it) } }
    }

    override fun get(
        actor: Actor,
        id: UUID,
    ): Migration {
        requireAdmin(actor)
        return tx.execute { view(actor, row(actor, id)) }
    }

    override fun create(
        actor: Actor,
        name: String,
        contextId: UUID,
        fileName: String,
        bytes: ByteArray,
    ): Migration {
        requireAdmin(actor)
        val context = contexts.get(actor, ContextId(contextId))
        if (!context.kind.holdsObjects) {
            throw ValidationException(
                "INVALID_MIGRATION",
                "Choose a product, library or project",
            )
        }
        Packages.read(bytes)
        val row =
            MigrationRow(
                UuidV7.generate(clock),
                name.trim().ifEmpty { fileName },
                contextId,
                fileName,
                bytes.size.toLong(),
                MigrationMappings(),
                null,
                clock.instant(),
                actor.principal.value,
            )
        tx.executeWithoutResult { repository.insert(actor.tenant.value, row, bytes) }
        return get(actor, row.id)
    }

    override fun updateMappings(
        actor: Actor,
        id: UUID,
        mappings: MigrationMappings,
    ): Migration {
        requireAdmin(actor)
        row(actor, id)
        tx.executeWithoutResult { repository.setMappings(id, mappings) }
        return get(actor, id)
    }

    override fun items(
        actor: Actor,
        id: UUID,
    ): List<MigrationItem> {
        requireAdmin(actor)
        row(actor, id)
        return repository.items(id)
    }

    override fun run(
        actor: Actor,
        id: UUID,
    ): Job {
        val m = get(actor, id)
        if (m.analysis.errors > 0) {
            throw ValidationException(
                "INVALID_MIGRATION",
                "Fix the ${m.analysis.errors} errors first: map what isn't matched",
            )
        }
        val job =
            jobs.submit(
                actor,
                SubmitJob(MigrationJob.TYPE, mapOf("migrationId" to id.toString(), "name" to m.name)),
            )
        tx.executeWithoutResult { repository.setJob(id, job.id) }
        return job
    }

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        row(actor, id)
        tx.executeWithoutResult { repository.delete(id) }
    }
}

/** Runs (or resumes) a migration: objects with their history and files, then structure and links. */
@Component
internal class MigrationJob(
    private val repository: MigrationRepository,
    private val engine: MigrationEngine,
    private val clock: Clock,
) : JobHandler {
    override val type = TYPE
    override val label = "Migration"
    override val description = "Brings objects with their history from another PLM system"
    override val adminOnly = true
    override val maxAttempts = 1

    override fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) {
        if (payload["migrationId"] == null) throw ValidationException("INVALID_MIGRATION", "Which migration?")
    }

    override fun title(payload: Map<String, Any?>) = "Migration: ${payload["name"]}"

    override fun run(context: JobContext): Map<String, Any?> {
        val actor = context.actor
        val id = UUID.fromString(context.payload["migrationId"].toString())
        val row = repository.find(actor.tenant.value, id) ?: throw JobPermanentFailure("The migration was deleted")
        val bytes = repository.bytes(id)
        val contents = Packages.read(bytes)
        val done = repository.done(id).keys
        val todo = contents.manifest.objects.count { it.id !in done }
        val total = todo + contents.manifest.objects.size
        var step = 0
        var ok = 0
        var failed = 0

        fun progress() = context.progress(++step, total, "$ok brought in, $failed failed")
        val temp = Files.createTempFile("plm-migration-", ".zip")
        try {
            Files.write(temp, bytes)
            ZipFile(temp.toFile()).use { zip ->
                engine.runObjects(actor, row.contextId, contents, zip, row.mappings, done, { o ->
                    repository.record(id, o.legacyId, o.number, o.masterId, o.ok, o.message, clock.instant(), o.updated)
                    if (o.ok) ok++ else failed++
                    if (!o.ok) context.log("${o.number}: ${o.message}", "WARN")
                    progress()
                }, context::checkCancelled)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        val objects = repository.done(id)
        var linkFailures = 0
        engine.runLinks(
            actor,
            contents,
            objects,
            repository.linksPending(
                id,
            ),
            repository.updated(id),
            { legacy, linked, message ->
                repository.linksDone(id, legacy, linked, message, clock.instant())
                if (!linked) {
                    linkFailures++
                    context.log("$legacy: $message", "WARN")
                }
                progress()
            },
            context::checkCancelled,
        )
        val (allDone, allFailed) = repository.counts(id)
        return mapOf(
            "broughtIn" to ok,
            "failed" to failed,
            "linkFailures" to linkFailures,
            "done" to allDone,
            "failedTotal" to allFailed,
        )
    }

    companion object {
        const val TYPE = "migration.run"
    }
}
