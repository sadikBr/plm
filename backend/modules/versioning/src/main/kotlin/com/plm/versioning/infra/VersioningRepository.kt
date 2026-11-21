package com.plm.versioning.infra

import com.plm.access.api.ContextId
import com.plm.access.api.SqlPredicate
import com.plm.lifecycle.api.LifecycleTemplateId
import com.plm.platform.error.ConflictException
import com.plm.platform.id.TenantId
import com.plm.types.api.RevisionScheme
import com.plm.types.api.TypeId
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.IterationId
import com.plm.versioning.api.IterationStatus
import com.plm.versioning.api.MasterId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class MasterRow(
    val id: MasterId,
    val typeId: TypeId,
    val contextId: ContextId,
    val number: String,
    val name: String,
    val latestIterationId: IterationId,
    val lifecycleId: LifecycleTemplateId,
    val revisionScheme: RevisionScheme,
    val createdAt: Instant,
    val createdBy: String,
    /** Derived: owner of the working copy, if any. */
    val checkedOutBy: String?,
    val folderId: UUID? = null,
    val owner: String? = null,
    val reservedBy: String? = null,
)

@Repository
internal class VersioningRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()
    private val attrsType = object : TypeReference<Map<String, Any?>>() {}

    private val masterMapper =
        RowMapper { rs, _ ->
            MasterRow(
                id = MasterId(rs.uuid("id")),
                typeId = TypeId(rs.uuid("type_id")),
                contextId = ContextId(rs.uuid("context_id")),
                number = rs.getString("number"),
                name = rs.getString("name"),
                latestIterationId = IterationId(rs.uuid("latest_iteration_id")),
                lifecycleId = LifecycleTemplateId(rs.uuid("lifecycle_id")),
                revisionScheme = RevisionScheme.valueOf(rs.getString("revision_scheme")),
                createdAt = rs.instant("created_at")!!,
                createdBy = rs.getString("created_by"),
                checkedOutBy = rs.getString("checked_out_by"),
                folderId = rs.getObject("folder_id", UUID::class.java),
                owner = rs.getString("owner"),
                reservedBy = rs.getString("reserved_by"),
            )
        }

    private val iterationMapper =
        RowMapper { rs, _ ->
            Iteration(
                id = IterationId(rs.uuid("id")),
                masterId = MasterId(rs.uuid("master_id")),
                revision = rs.getString("revision"),
                iteration = rs.getInt("iteration"),
                status = IterationStatus.valueOf(rs.getString("status")),
                state = rs.getString("state"),
                attributes = json.readValue(rs.getString("attrs"), attrsType),
                basedOnId = rs.getObject("based_on_id", UUID::class.java)?.let(::IterationId),
                comment = rs.getString("comment"),
                createdAt = rs.instant("created_at")!!,
                createdBy = rs.getString("created_by"),
                checkedInAt = rs.instant("checked_in_at"),
                checkedOutBy = rs.getString("checked_out_by"),
                oneOff = rs.getObject("one_off") as Int?,
                oneOffFor = rs.getString("one_off_for"),
            )
        }

    fun nextNumber(): String =
        "%010d".format(jdbc.sql("select nextval('object_number_seq')").query(Long::class.java).single())

    /**
     * With [forUpdate], locks the master row until the transaction ends. The working-copy owner is
     * read in a second statement: Postgres cannot lock the nullable side of an outer join.
     */
    fun findMaster(
        tenant: TenantId,
        id: MasterId,
        forUpdate: Boolean,
    ): MasterRow? {
        val lock = if (forUpdate) " for update" else ""
        val found =
            jdbc
                .sql("select *, null as checked_out_by from object_master where tenant_id = :tenant and id = :id$lock")
                .param("tenant", tenant.value)
                .param("id", id.value)
                .query(masterMapper)
                .optional()
                .orElse(null) ?: return null
        return found.copy(checkedOutBy = workingCopy(found.id)?.checkedOutBy)
    }

    /** One page of masters with their latest iteration, restricted by [filter]. Two queries per page. */
    fun list(
        tenant: TenantId,
        context: ContextId?,
        text: String?,
        filter: SqlPredicate,
        limit: Int,
        after: MasterId?,
        folder: UUID? = null,
        topLevelOnly: Boolean = false,
        ids: List<UUID>? = null,
        checkedOutBy: String? = null,
    ): List<Pair<MasterRow, Iteration>> {
        if (ids != null && ids.isEmpty()) return emptyList()
        val masters =
            jdbc
                .sql(
                    """
                    select m.*, wc.checked_out_by
                    from object_master m
                    join object_iteration i on i.id = m.latest_iteration_id
                    left join object_iteration wc on wc.master_id = m.id and wc.status = 'WORKING'
                    where m.tenant_id = :tenant
                      and (cast(:context as uuid) is null or m.context_id = :context)
                      and (cast(:pattern as text) is null or m.number ilike :pattern or m.name ilike :pattern)
                      and (cast(:after as uuid) is null or m.id > :after)
                      and (cast(:folder as uuid) is null or m.folder_id = :folder)
                      and (not :topOnly or m.folder_id is null)
                      and (cast(:ids as uuid[]) is null or m.id = any(cast(:ids as uuid[])))
                      and (cast(:cob as text) is null or wc.checked_out_by = :cob)
                      and ${filter.sql}
                    order by m.id
                    limit :limit
                    """,
                ).params(filter.params)
                .param("tenant", tenant.value)
                .param("context", context?.value)
                .param(
                    "pattern",
                    text?.let {
                        "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") +
                            "%"
                    },
                ).param("after", after?.value)
                .param("folder", folder)
                .param("topOnly", topLevelOnly)
                .param("ids", ids?.toTypedArray())
                .param("cob", checkedOutBy)
                .param("limit", limit)
                .query(masterMapper)
                .list()
        if (masters.isEmpty()) return emptyList()
        val latest =
            jdbc
                .sql("select * from object_iteration where id in (:ids)")
                .param("ids", masters.map { it.latestIterationId.value })
                .query(iterationMapper)
                .list()
                .associateBy { it.id }
        return masters.map { it to latest.getValue(it.latestIterationId) }
    }

    /**
     * Masters whose latest iteration has [target] as the value (or one of the values) of one of the
     * reference attributes [names], with that attribute's name. Backed by the GIN index on attrs.
     */
    fun referencing(
        tenant: TenantId,
        target: UUID,
        names: Set<String>,
        filter: SqlPredicate,
        limit: Int = 500,
    ): List<Pair<MasterRow, String>> {
        if (names.isEmpty()) return emptyList()
        val results = mutableListOf<Pair<MasterRow, String>>()
        for (name in names.sorted()) {
            jdbc
                .sql(
                    """
                    select m.*, null as checked_out_by
                    from object_master m
                    join object_iteration i on i.id = m.latest_iteration_id
                    where m.tenant_id = :tenant
                      and (i.attrs @> jsonb_build_object(cast(:name as text), cast(:target as text))
                        or i.attrs @> jsonb_build_object(cast(:name as text), jsonb_build_array(cast(:target as text))))
                      and ${filter.sql}
                    order by m.number
                    limit :limit
                    """,
                ).params(filter.params)
                .param("tenant", tenant.value)
                .param("name", name)
                .param("target", target.toString())
                .param("limit", limit)
                .query(masterMapper)
                .list()
                .forEach { results += it to name }
        }
        return results
    }

    fun insertMaster(
        tenant: TenantId,
        master: MasterRow,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into object_master
                        (id, tenant_id, type_id, context_id, number, name, latest_iteration_id, lifecycle_id,
                         revision_scheme, created_at, created_by)
                    values (:id, :tenant, :type, :context, :number, :name, :latest, :lifecycle, :revisionScheme,
                            :createdAt, :createdBy)
                    """,
                ).param("id", master.id.value)
                .param("tenant", tenant.value)
                .param("type", master.typeId.value)
                .param("context", master.contextId.value)
                .param("number", master.number)
                .param("name", master.name)
                .param("latest", master.latestIterationId.value)
                .param("lifecycle", master.lifecycleId.value)
                .param("revisionScheme", master.revisionScheme.name)
                .param("createdAt", Timestamp.from(master.createdAt))
                .param("createdBy", master.createdBy)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_NUMBER",
                "An object numbered '${master.number}' already exists",
                mapOf("number" to master.number),
            ).apply { initCause(e) }
        }
    }

    fun insertIteration(
        tenant: TenantId,
        it: Iteration,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into object_iteration
                        (id, tenant_id, master_id, revision, iteration, status, state, attrs, based_on_id,
                         comment, created_at, created_by, checked_in_at, checked_out_by, one_off, one_off_for)
                    values (:id, :tenant, :master, :revision, :iteration, :status, :state, cast(:attrs as jsonb),
                            :basedOn, :comment, :createdAt, :createdBy, :checkedInAt, :checkedOutBy, :oneOff, :oneOffFor)
                    """,
                ).param("id", it.id.value)
                .param("tenant", tenant.value)
                .param("master", it.masterId.value)
                .param("revision", it.revision)
                .param("iteration", it.iteration)
                .param("status", it.status.name)
                .param("state", it.state)
                .param("attrs", json.writeValueAsString(it.attributes))
                .param("basedOn", it.basedOnId?.value)
                .param("comment", it.comment)
                .param("createdAt", Timestamp.from(it.createdAt))
                .param("createdBy", it.createdBy)
                .param("checkedInAt", it.checkedInAt?.let(Timestamp::from))
                .param("checkedOutBy", it.checkedOutBy)
                .param("oneOff", it.oneOff)
                .param("oneOffFor", it.oneOffFor)
                .update()
        } catch (e: DuplicateKeyException) {
            // Unreachable while the master row lock is held; kept as a safety net.
            throw ConflictException("ALREADY_CHECKED_OUT", "Object is already checked out").apply { initCause(e) }
        }
    }

    fun iteration(id: IterationId): Iteration =
        jdbc
            .sql("select * from object_iteration where id = :id")
            .param("id", id.value)
            .query(iterationMapper)
            .single()

    fun workingCopy(master: MasterId): Iteration? =
        jdbc
            .sql("select * from object_iteration where master_id = :master and status = 'WORKING'")
            .param("master", master.value)
            .query(iterationMapper)
            .optional()
            .orElse(null)

    fun history(master: MasterId): List<Iteration> =
        jdbc
            .sql("select * from object_iteration where master_id = :master and status = 'CHECKED_IN' order by seq desc")
            .param("master", master.value)
            .query(iterationMapper)
            .list()

    fun updateAttributes(
        id: IterationId,
        attributes: Map<String, Any?>,
    ) {
        jdbc
            .sql("update object_iteration set attrs = cast(:attrs as jsonb) where id = :id and status = 'WORKING'")
            .param("attrs", json.writeValueAsString(attributes))
            .param("id", id.value)
            .update()
            .also { check(it == 1) { "working copy $id vanished" } }
    }

    fun checkIn(it: Iteration) {
        jdbc
            .sql(
                """
                update object_iteration
                set status = 'CHECKED_IN', comment = :comment, checked_in_at = :checkedInAt, checked_out_by = null
                where id = :id and status = 'WORKING'
                """,
            ).param("comment", it.comment)
            .param("checkedInAt", Timestamp.from(it.checkedInAt))
            .param("id", it.id.value)
            .update()
            .also { check(it == 1) { "working copy vanished" } }
    }

    fun deleteWorkingCopy(id: IterationId) {
        jdbc
            .sql("delete from object_iteration where id = :id and status = 'WORKING'")
            .param("id", id.value)
            .update()
            .also { check(it == 1) { "working copy $id vanished" } }
    }

    /** Lifecycle state is the one thing that may change on a checked-in iteration (the history trigger allows it). */
    fun rename(
        master: MasterId,
        number: String,
        name: String,
    ) {
        try {
            jdbc
                .sql("update object_master set number = :number, name = :name where id = :id")
                .param("number", number)
                .param("name", name)
                .param("id", master.value)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_NUMBER",
                "An object numbered '$number' already exists",
                mapOf("number" to number),
            ).apply { initCause(e) }
        }
    }

    fun setRevisionScheme(
        master: MasterId,
        scheme: RevisionScheme,
    ) {
        jdbc
            .sql(
                "update object_master set revision_scheme = :scheme where id = :id",
            ).param("scheme", scheme.name)
            .param("id", master.value)
            .update()
    }

    fun setLifecycle(
        master: MasterId,
        lifecycle: LifecycleTemplateId,
    ) {
        jdbc
            .sql(
                "update object_master set lifecycle_id = :lifecycle where id = :id",
            ).param("lifecycle", lifecycle.value)
            .param("id", master.value)
            .update()
    }

    fun setState(
        iteration: IterationId,
        state: String,
    ) {
        jdbc
            .sql("update object_iteration set state = :state where id = :id and status = 'CHECKED_IN'")
            .param("state", state)
            .param("id", iteration.value)
            .update()
            .also { check(it == 1) { "iteration $iteration is not checked in" } }
    }

    fun setLatest(
        master: MasterId,
        iteration: IterationId,
    ) {
        jdbc
            .sql("update object_master set latest_iteration_id = :iteration where id = :master")
            .param("iteration", iteration.value)
            .param("master", master.value)
            .update()
    }

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()
}
