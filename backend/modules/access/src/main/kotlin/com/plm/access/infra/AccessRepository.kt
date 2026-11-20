package com.plm.access.infra

import com.plm.access.api.ContextId
import com.plm.access.api.TeamMember
import com.plm.access.api.TeamRole
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

/**
 * The permission rule, written once and used in two ways: as a scalar query for one target
 * ([permissionBits]) and as an EXISTS predicate inside list queries ([permissionPredicate]).
 *
 * `mc` walks from the target context up to every ancestor where the principal has a team role.
 * `pc` walks from the target context up to every ancestor that defines a policy for that role.
 */
private fun permissionJoin(
    prefix: String,
    context: String,
) = """
    from context_closure ${prefix}mc
    join context_member ${prefix}tm
      on ${prefix}tm.context_id = ${prefix}mc.ancestor_id
     and ${prefix}tm.tenant_id = :${prefix}tenant
     and ${prefix}tm.principal_id = :${prefix}principal
    join context_closure ${prefix}pc on ${prefix}pc.descendant_id = $context
    join access_policy ${prefix}ap
      on ${prefix}ap.context_id = ${prefix}pc.ancestor_id
     and ${prefix}ap.role = ${prefix}tm.role
    where ${prefix}mc.descendant_id = $context
"""

private val SQL_IDENTIFIER = Regex("^[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?$")

@Repository
internal class AccessRepository(
    private val jdbc: JdbcClient,
) {
    fun permissionBits(
        tenant: TenantId,
        principal: PrincipalId,
        context: ContextId,
        type: TypeId?,
        state: String?,
    ): Int =
        jdbc
            .sql(
                """
                select coalesce(bit_or(ap.permissions), 0)
                ${permissionJoin("", ":context")}
                  and (ap.type_id is null or ap.type_id = cast(:type as uuid))
                  and (ap.state is null or ap.state = cast(:state as text))
                """,
            ).param("tenant", tenant.value)
            .param("principal", principal.value)
            .param("context", context.value)
            .param("type", type?.value)
            .param("state", state)
            .query(Int::class.java)
            .single()

    fun permissionPredicate(
        contextColumn: String,
        typeColumn: String,
        stateColumn: String,
    ): String {
        listOf(contextColumn, typeColumn, stateColumn).forEach {
            require(SQL_IDENTIFIER.matches(it)) { "Not a plain column reference: '$it'" }
        }
        return """
            exists (
                select 1
                ${permissionJoin("acl_", contextColumn)}
                  and (acl_ap.type_id is null or acl_ap.type_id = $typeColumn)
                  and (acl_ap.state is null or acl_ap.state = $stateColumn)
                  and (acl_ap.permissions & :acl_bit) <> 0
            )
            """.trimIndent()
    }

    /** True if the principal is on the team of [context], of an ancestor, or of a descendant. */
    fun isVisible(
        tenant: TenantId,
        principal: PrincipalId,
        context: ContextId,
    ): Boolean =
        jdbc
            .sql(
                """
                select exists (
                    select 1 from context_member tm
                    join context_closure cc
                      on (cc.ancestor_id = tm.context_id and cc.descendant_id = :context)
                      or (cc.descendant_id = tm.context_id and cc.ancestor_id = :context)
                    where tm.tenant_id = :tenant and tm.principal_id = :principal
                )
                """,
            ).param("tenant", tenant.value)
            .param("principal", principal.value)
            .param("context", context.value)
            .query(Boolean::class.java)
            .single()

    fun insertClosure(
        tenant: TenantId,
        context: ContextId,
        parent: ContextId,
    ) {
        jdbc
            .sql(
                """
                insert into context_closure (tenant_id, ancestor_id, descendant_id, depth)
                select :tenant, ancestor_id, :context, depth + 1 from context_closure where descendant_id = :parent
                union all
                select :tenant, :context, :context, 0
                """,
            ).param("tenant", tenant.value)
            .param("context", context.value)
            .param("parent", parent.value)
            .update()
    }

    private val memberMapper =
        RowMapper { rs, _ ->
            TeamMember(
                contextId = ContextId(rs.getObject("context_id", UUID::class.java)),
                principalId = PrincipalId(rs.getString("principal_id")),
                role = TeamRole.valueOf(rs.getString("role")),
                addedAt = rs.getTimestamp("added_at").toInstant(),
                addedBy = rs.getString("added_by"),
            )
        }

    fun members(context: ContextId): List<TeamMember> =
        jdbc
            .sql("select * from context_member where context_id = :context order by principal_id")
            .param("context", context.value)
            .query(memberMapper)
            .list()

    fun member(
        context: ContextId,
        principal: PrincipalId,
    ): TeamMember? =
        jdbc
            .sql("select * from context_member where context_id = :context and principal_id = :principal")
            .param("context", context.value)
            .param("principal", principal.value)
            .query(memberMapper)
            .optional()
            .orElse(null)

    fun putMember(
        tenant: TenantId,
        member: TeamMember,
    ) {
        jdbc
            .sql(
                """
                insert into context_member (tenant_id, context_id, principal_id, role, added_at, added_by)
                values (:tenant, :context, :principal, :role, :addedAt, :addedBy)
                on conflict (context_id, principal_id) do update
                    set role = excluded.role, added_at = excluded.added_at, added_by = excluded.added_by
                """,
            ).param("tenant", tenant.value)
            .param("context", member.contextId.value)
            .param("principal", member.principalId.value)
            .param("role", member.role.name)
            .param("addedAt", Timestamp.from(member.addedAt))
            .param("addedBy", member.addedBy)
            .update()
    }

    fun removeMember(
        context: ContextId,
        principal: PrincipalId,
    ): Int =
        jdbc
            .sql("delete from context_member where context_id = :context and principal_id = :principal")
            .param("context", context.value)
            .param("principal", principal.value)
            .update()
}
