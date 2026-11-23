package com.plm.retention.domain

import com.plm.retention.api.Archive
import com.plm.retention.api.LegalHold
import com.plm.retention.api.RetentionRule
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class ArchiveRow(
    val view: Archive,
    val typeId: UUID,
    val tenant: UUID,
)

@Repository
internal class RetentionRepository(
    private val jdbc: JdbcClient,
) {
    private fun ResultSet.rule() =
        RetentionRule(
            getObject("id", UUID::class.java),
            getString("name"),
            getObject("type_id", UUID::class.java),
            getString("state"),
            getInt("age_days"),
            getString("action"),
            getBoolean("active"),
            getTimestamp("created_at").toInstant(),
        )

    fun rules(tenant: UUID): List<RetentionRule> =
        jdbc
            .sql("select * from retention_rule where tenant_id = :t order by name")
            .param("t", tenant)
            .query {
                rs,
                _,
                ->
                rs.rule()
            }.list()

    fun saveRule(
        tenant: UUID,
        r: RetentionRule,
        by: String,
    ) {
        jdbc
            .sql(
                """
                insert into retention_rule (id, tenant_id, name, type_id, state, age_days, action, active, created_at, created_by)
                values (:id, :t, :name, :type, :state, :age, :action, :active, :at, :by)
                on conflict (id) do update set name = excluded.name, type_id = excluded.type_id, state = excluded.state,
                    age_days = excluded.age_days, action = excluded.action, active = excluded.active
                """,
            ).param("id", r.id)
            .param("t", tenant)
            .param("name", r.name)
            .param("type", r.typeId)
            .param("state", r.state)
            .param("age", r.ageDays)
            .param("action", r.action)
            .param("active", r.active)
            .param("at", Timestamp.from(r.createdAt))
            .param("by", by)
            .update()
    }

    fun deleteRule(
        tenant: UUID,
        id: UUID,
    ): Int =
        jdbc
            .sql(
                "delete from retention_rule where tenant_id = :t and id = :id",
            ).param("t", tenant)
            .param("id", id)
            .update()

    private fun ResultSet.hold(): LegalHold {
        val id = getObject("id", UUID::class.java)
        val items =
            jdbc
                .sql("select object_id, context_id from legal_hold_item where hold_id = :h")
                .param("h", id)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
                .list()
        return LegalHold(
            id,
            getString("name"),
            getString("reason"),
            items.mapNotNull { it.first },
            items.mapNotNull { it.second },
            getTimestamp("created_at").toInstant(),
            getString("created_by"),
            getTimestamp("released_at")?.toInstant(),
            getString("released_by"),
        )
    }

    fun holds(tenant: UUID): List<LegalHold> =
        jdbc
            .sql("select * from legal_hold where tenant_id = :t order by created_at desc")
            .param("t", tenant)
            .query {
                rs,
                _,
                ->
                rs.hold()
            }.list()

    fun hold(id: UUID): LegalHold? =
        jdbc
            .sql("select * from legal_hold where id = :id")
            .param("id", id)
            .query {
                rs,
                _,
                ->
                rs.hold()
            }.optional()
            .orElse(null)

    fun insertHold(
        tenant: UUID,
        h: LegalHold,
    ) {
        jdbc
            .sql(
                "insert into legal_hold (id, tenant_id, name, reason, created_at, created_by) values (:id, :t, :n, :r, :at, :by)",
            ).param("id", h.id)
            .param("t", tenant)
            .param("n", h.name)
            .param("r", h.reason)
            .param("at", Timestamp.from(h.createdAt))
            .param("by", h.createdBy)
            .update()
        h.objectIds.distinct().forEach {
            jdbc
                .sql(
                    "insert into legal_hold_item (hold_id, object_id) values (:h, :o)",
                ).param("h", h.id)
                .param("o", it)
                .update()
        }
        h.contextIds.distinct().forEach {
            jdbc
                .sql(
                    "insert into legal_hold_item (hold_id, context_id) values (:h, :c)",
                ).param("h", h.id)
                .param("c", it)
                .update()
        }
    }

    fun release(
        id: UUID,
        at: Instant,
        by: String,
    ) {
        jdbc
            .sql("update legal_hold set released_at = :at, released_by = :by where id = :id and released_at is null")
            .param("id", id)
            .param("at", Timestamp.from(at))
            .param("by", by)
            .update()
    }

    /** Active holds on [objectId] or on any of [contextIds] (the object's context and its ancestors). */
    fun activeHolds(
        tenant: UUID,
        objectId: UUID,
        contextIds: List<UUID>,
    ): List<LegalHold> =
        jdbc
            .sql(
                """
                select distinct h.* from legal_hold h join legal_hold_item i on i.hold_id = h.id
                where h.tenant_id = :t and h.released_at is null
                  and (i.object_id = :o or i.context_id in (:contexts))
                """,
            ).param("t", tenant)
            .param("o", objectId)
            .param("contexts", contextIds.ifEmpty { listOf(UUID(0, 0)) })
            .query { rs, _ -> rs.hold() }
            .list()

    private fun ResultSet.archive() =
        ArchiveRow(
            Archive(
                getObject("id", UUID::class.java),
                getObject("object_id", UUID::class.java),
                getString("number"),
                getString("name"),
                getObject("context_id", UUID::class.java),
                getInt("iterations"),
                getInt("files"),
                getLong("size"),
                getTimestamp("archived_at").toInstant(),
                getString("archived_by"),
                getTimestamp("restored_at")?.toInstant(),
            ),
            getObject("type_id", UUID::class.java),
            getObject("tenant_id", UUID::class.java),
        )

    private val archiveColumns =
        "id, tenant_id, object_id, number, name, type_id, context_id, iterations, files, length(package) as size, " +
            "archived_at, archived_by, restored_at"

    fun archives(tenant: UUID): List<Archive> =
        jdbc
            .sql("select $archiveColumns from archive where tenant_id = :t order by archived_at desc")
            .param("t", tenant)
            .query { rs, _ -> rs.archive().view }
            .list()

    fun archive(id: UUID): ArchiveRow? =
        jdbc
            .sql("select $archiveColumns from archive where id = :id")
            .param("id", id)
            .query {
                rs,
                _,
                ->
                rs.archive()
            }.optional()
            .orElse(null)

    fun archivePackage(id: UUID): ByteArray =
        jdbc
            .sql(
                "select package from archive where id = :id",
            ).param("id", id)
            .query { rs, _ -> rs.getBytes(1) }
            .single()

    fun insertArchive(
        tenant: UUID,
        a: Archive,
        typeId: UUID,
        bytes: ByteArray,
    ) {
        jdbc
            .sql(
                """
                insert into archive (id, tenant_id, object_id, number, name, type_id, context_id, package, iterations, files, archived_at, archived_by)
                values (:id, :t, :o, :number, :name, :type, :context, :package, :iterations, :files, :at, :by)
                """,
            ).param("id", a.id)
            .param("t", tenant)
            .param("o", a.objectId)
            .param("number", a.number)
            .param("name", a.name)
            .param("type", typeId)
            .param("context", a.contextId)
            .param("package", bytes)
            .param("iterations", a.iterations)
            .param("files", a.files)
            .param("at", Timestamp.from(a.archivedAt))
            .param("by", a.archivedBy)
            .update()
    }

    fun restored(
        id: UUID,
        at: Instant,
        by: String,
    ) {
        jdbc
            .sql(
                "update archive set restored_at = :at, restored_by = :by where id = :id",
            ).param("id", id)
            .param("at", Timestamp.from(at))
            .param("by", by)
            .update()
    }
}
