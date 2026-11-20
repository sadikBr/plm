package com.plm.access.infra

import com.plm.access.api.Context
import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.platform.error.ConflictException
import com.plm.platform.id.TenantId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class ContextRepository(
    private val jdbc: JdbcClient,
) {
    private val mapper =
        RowMapper { rs, _ ->
            Context(
                id = ContextId(rs.getObject("id", UUID::class.java)),
                parentId = rs.getObject("parent_id", UUID::class.java)?.let(::ContextId),
                kind = ContextKind.valueOf(rs.getString("kind")),
                name = rs.getString("name"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                createdBy = rs.getString("created_by"),
            )
        }

    fun find(
        tenant: TenantId,
        id: ContextId,
    ): Context? =
        jdbc
            .sql("select * from context where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .query(mapper)
            .optional()
            .orElse(null)

    fun children(
        tenant: TenantId,
        parent: ContextId,
    ): List<Context> =
        jdbc
            .sql("select * from context where tenant_id = :tenant and parent_id = :parent order by name")
            .param("tenant", tenant.value)
            .param("parent", parent.value)
            .query(mapper)
            .list()

    /** The context and its ancestors, root first. */
    fun path(context: ContextId): List<Context> =
        jdbc
            .sql(
                """
                select c.* from context_closure cc join context c on c.id = cc.ancestor_id
                where cc.descendant_id = :context order by cc.depth desc
                """,
            ).param("context", context.value)
            .query(mapper)
            .list()

    /**
     * Contexts whose name contains [text] (case-insensitive) that [principal] can see: on the team
     * of the context, an ancestor or a descendant (the same rule as visibility). Site admins see all.
     */
    fun search(
        tenant: TenantId,
        principal: String,
        siteAdmin: Boolean,
        text: String,
        limit: Int,
    ): List<Context> {
        val pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return jdbc
            .sql(
                """
                select c.* from context c
                where c.tenant_id = :tenant and c.name ilike :pattern
                  and (:admin or exists (
                    select 1 from team_membership tm
                    join context_closure cc
                      on (cc.ancestor_id = tm.context_id and cc.descendant_id = c.id)
                      or (cc.descendant_id = tm.context_id and cc.ancestor_id = c.id)
                    where tm.tenant_id = :tenant and tm.principal_id = :principal
                  ))
                order by c.name
                limit :limit
                """,
            ).param("tenant", tenant.value)
            .param("pattern", pattern)
            .param("admin", siteAdmin)
            .param("principal", principal)
            .param("limit", limit)
            .query(mapper)
            .list()
    }

    fun insert(
        tenant: TenantId,
        context: Context,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into context (id, tenant_id, parent_id, kind, name, created_at, created_by)
                    values (:id, :tenant, :parent, :kind, :name, :createdAt, :createdBy)
                    """,
                ).param("id", context.id.value)
                .param("tenant", tenant.value)
                .param("parent", context.parentId?.value)
                .param("kind", context.kind.name)
                .param("name", context.name)
                .param("createdAt", Timestamp.from(context.createdAt))
                .param("createdBy", context.createdBy)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_CONTEXT_NAME",
                "A context named '${context.name}' already exists here",
                mapOf("name" to context.name),
            ).apply { initCause(e) }
        }
    }
}
