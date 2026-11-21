package com.plm.versioning.infra

import com.plm.platform.id.TenantId
import com.plm.versioning.api.DeleteRules
import com.plm.versioning.api.Deleter
import com.plm.versioning.api.Folder
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
internal class OrganizingRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    private val folderSql =
        """
        select f.*,
               (select count(*) from folder c where c.parent_id = f.id) as folders,
               (select count(*) from object_master m where m.folder_id = f.id) as objects
        from folder f
        """.trimIndent()

    private fun map(rs: ResultSet) =
        Folder(
            id = rs.getObject("id", UUID::class.java),
            contextId = rs.getObject("context_id", UUID::class.java),
            parentId = rs.getObject("parent_id", UUID::class.java),
            name = rs.getString("name"),
            folders = rs.getInt("folders"),
            objects = rs.getInt("objects"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            createdBy = rs.getString("created_by"),
        )

    fun folders(
        tenant: TenantId,
        context: UUID,
        parent: UUID?,
    ): List<Folder> =
        jdbc
            .sql(
                "$folderSql where f.tenant_id = :tenant and f.context_id = :context " +
                    "and f.parent_id is not distinct from cast(:parent as uuid) order by lower(f.name)",
            ).param("tenant", tenant.value)
            .param("context", context)
            .param("parent", parent)
            .query { rs, _ -> map(rs) }
            .list()

    fun folder(
        tenant: TenantId,
        id: UUID,
    ): Folder? =
        jdbc
            .sql("$folderSql where f.tenant_id = :tenant and f.id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    /** False when the name is taken there. */
    fun insertFolder(
        tenant: TenantId,
        id: UUID,
        context: UUID,
        parent: UUID?,
        name: String,
        by: String,
        now: Instant,
    ): Boolean =
        try {
            jdbc
                .sql(
                    """
                    insert into folder (id, tenant_id, context_id, parent_id, name, created_at, created_by)
                    values (:id, :tenant, :context, :parent, :name, :now, :by)
                    """.trimIndent(),
                ).param("id", id)
                .param("tenant", tenant.value)
                .param("context", context)
                .param("parent", parent)
                .param("name", name)
                .param("now", Timestamp.from(now))
                .param("by", by)
                .update()
            true
        } catch (e: DuplicateKeyException) {
            false
        }

    fun renameFolder(
        id: UUID,
        name: String,
    ): Boolean =
        try {
            jdbc
                .sql("update folder set name = :name where id = :id")
                .param("id", id)
                .param("name", name)
                .update()
            true
        } catch (e: DuplicateKeyException) {
            false
        }

    fun deleteFolder(id: UUID) {
        jdbc.sql("delete from folder where id = :id").param("id", id).update()
    }

    fun move(
        master: UUID,
        context: UUID,
        folder: UUID?,
    ) {
        jdbc
            .sql("update object_master set context_id = :context, folder_id = :folder where id = :id")
            .param("id", master)
            .param("context", context)
            .param("folder", folder)
            .update()
    }

    fun setOwner(
        master: UUID,
        owner: String,
    ) {
        jdbc
            .sql(
                "update object_master set owner = :owner where id = :id",
            ).param("id", master)
            .param("owner", owner)
            .update()
    }

    fun setReservation(
        master: UUID,
        by: String?,
        now: Instant?,
    ) {
        jdbc
            .sql("update object_master set reserved_by = :by, reserved_at = :at where id = :id")
            .param("id", master)
            .param("by", by)
            .param("at", now?.let { Timestamp.from(it) })
            .update()
    }

    /**
     * Removes an object with all its iterations. The history guard lets this one master's
     * iterations go, for this transaction only.
     */
    fun deleteObject(master: UUID) {
        jdbc
            .sql(
                "select set_config('plm.deleting_master', :id, true)",
            ).param("id", master.toString())
            .query()
            .singleRow()
        // One statement: references between the object's own iterations are checked at its end.
        jdbc.sql("delete from object_iteration where master_id = :id").param("id", master).update()
        jdbc.sql("delete from object_master where id = :id").param("id", master).update()
    }

    fun deleteRules(tenant: TenantId): DeleteRules? =
        jdbc
            .sql("select * from delete_rule where tenant_id = :tenant")
            .param("tenant", tenant.value)
            .query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                DeleteRules(
                    states = json.readValue(rs.getString("states"), List::class.java) as List<String>,
                    who =
                        (
                            json.readValue(
                                rs.getString("who"),
                                List::class.java,
                            ) as List<String>
                        ).map { Deleter.valueOf(it) }.toSet(),
                )
            }.optional()
            .orElse(null)

    fun saveDeleteRules(
        tenant: TenantId,
        rules: DeleteRules,
        by: String,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into delete_rule (tenant_id, states, who, updated_at, updated_by)
                values (:tenant, cast(:states as jsonb), cast(:who as jsonb), :now, :by)
                on conflict (tenant_id) do update set states = excluded.states, who = excluded.who,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("states", json.writeValueAsString(rules.states))
            .param("who", json.writeValueAsString(rules.who.map { it.name }.sorted()))
            .param("now", Timestamp.from(now))
            .param("by", by)
            .update()
    }
}
