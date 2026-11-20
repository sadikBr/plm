package com.plm.access.infra

import com.plm.access.api.AccessPolicy
import com.plm.access.api.ContextId
import com.plm.access.api.Permission
import com.plm.access.api.PolicyEffect
import com.plm.access.api.PolicyId
import com.plm.access.api.SharedTeamId
import com.plm.access.api.TeamMember
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamRoleDefinition
import com.plm.platform.error.ConflictException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
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
    join team_membership ${prefix}tm
      on ${prefix}tm.context_id = ${prefix}mc.ancestor_id
     and ${prefix}tm.tenant_id = :${prefix}tenant
     and ${prefix}tm.principal_id = :${prefix}principal
    join context_closure ${prefix}pc on ${prefix}pc.descendant_id = $context
    join access_policy ${prefix}ap
      on ${prefix}ap.context_id = ${prefix}pc.ancestor_id
     and ${prefix}ap.role = ${prefix}tm.role
    where ${prefix}mc.descendant_id = $context
"""

/** True when the policy's type is null (all types) or is [targetType] or one of its supertypes. */
private fun typeMatches(
    prefix: String,
    targetType: String,
) = """
    (${prefix}ap.type_id is null or exists (
        select 1 from type_closure ${prefix}tc
        where ${prefix}tc.ancestor_id = ${prefix}ap.type_id and ${prefix}tc.descendant_id = $targetType
    ))
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
                select coalesce(bit_or(ap.permissions) filter (where ap.effect = 'ALLOW'), 0)
                     & ~coalesce(bit_or(ap.permissions) filter (where ap.effect = 'DENY'), 0)
                ${permissionJoin("", ":context")}
                  and ${typeMatches("", "cast(:type as uuid)")}
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

        fun matching(effect: String) =
            """
            (
                select 1
                ${permissionJoin("acl_", contextColumn)}
                  and acl_ap.effect = '$effect'
                  and ${typeMatches("acl_", typeColumn)}
                  and (acl_ap.state is null or acl_ap.state = $stateColumn)
                  and (acl_ap.permissions & :acl_bit) <> 0
            )
            """
        return "(exists ${matching("ALLOW")} and not exists ${matching("DENY")})".trimIndent()
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
                    select 1 from team_membership tm
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
                role = TeamRole(rs.getString("role")),
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
            .param("role", member.role.key)
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

    /** Members of [context] and of its ancestors, nearest context first. */
    fun effectiveMembers(
        context: ContextId,
        principal: PrincipalId? = null,
    ): List<TeamMember> =
        jdbc
            .sql(
                """
                select tm.* from context_closure cc
                join team_membership tm on tm.context_id = cc.ancestor_id
                where cc.descendant_id = :context
                  and (cast(:principal as text) is null or tm.principal_id = :principal)
                order by cc.depth, tm.principal_id
                """,
            ).param("context", context.value)
            .param("principal", principal?.value)
            .query {
                rs,
                i,
                ->
                memberMapper
                    .mapRow(
                        rs,
                        i,
                    ).copy(sharedTeamId = rs.getObject("shared_team_id", UUID::class.java)?.let(::SharedTeamId))
            }.list()

    // ---------- Policies ----------

    private val policyMapper =
        RowMapper { rs, _ ->
            AccessPolicy(
                id = PolicyId(rs.getObject("id", UUID::class.java)),
                contextId = ContextId(rs.getObject("context_id", UUID::class.java)),
                role = TeamRole(rs.getString("role")),
                typeId = rs.getObject("type_id", UUID::class.java)?.let(::TypeId),
                state = rs.getString("state"),
                effect = PolicyEffect.valueOf(rs.getString("effect")),
                permissions = Permission.fromBits(rs.getInt("permissions")),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                createdBy = rs.getString("created_by"),
            )
        }

    /** Policies defined on [context] or any ancestor, root first. */
    fun policiesInEffect(context: ContextId): List<AccessPolicy> =
        jdbc
            .sql(
                """
                select ap.* from context_closure pc
                join access_policy ap on ap.context_id = pc.ancestor_id
                where pc.descendant_id = :context
                order by pc.depth desc, ap.role, ap.type_id nulls first, ap.state nulls first, ap.effect
                """,
            ).param("context", context.value)
            .query(policyMapper)
            .list()

    /** The policies that apply to [principal] on a target (any effect, any permission). */
    fun matchingPolicies(
        tenant: TenantId,
        principal: PrincipalId,
        context: ContextId,
        type: TypeId?,
        state: String?,
    ): List<AccessPolicy> =
        jdbc
            .sql(
                """
                select distinct ap.*
                ${permissionJoin("", ":context")}
                  and ${typeMatches("", "cast(:type as uuid)")}
                  and (ap.state is null or ap.state = cast(:state as text))
                """,
            ).param("tenant", tenant.value)
            .param("principal", principal.value)
            .param("context", context.value)
            .param("type", type?.value)
            .param("state", state)
            .query(policyMapper)
            .list()

    fun policy(
        tenant: TenantId,
        id: PolicyId,
    ): AccessPolicy? =
        jdbc
            .sql("select * from access_policy where id = :id and tenant_id = :tenant")
            .param("id", id.value)
            .param("tenant", tenant.value)
            .query(policyMapper)
            .optional()
            .orElse(null)

    fun insertPolicy(
        tenant: TenantId,
        policy: AccessPolicy,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into access_policy
                        (id, tenant_id, context_id, role, type_id, state, effect, permissions, created_at, created_by)
                    values (:id, :tenant, :context, :role, :type, :state, :effect, :bits, :createdAt, :createdBy)
                    """,
                ).param("id", policy.id.value)
                .param("tenant", tenant.value)
                .param("context", policy.contextId.value)
                .param("role", policy.role.key)
                .param("type", policy.typeId?.value)
                .param("state", policy.state)
                .param("effect", policy.effect.name)
                .param("bits", policy.permissions.sumOf { it.bit })
                .param("createdAt", Timestamp.from(policy.createdAt))
                .param("createdBy", policy.createdBy)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_POLICY",
                "This context already has a ${policy.effect} policy for that role, type and state; change its permissions instead",
            ).apply { initCause(e) }
        }
    }

    fun updatePolicyPermissions(
        id: PolicyId,
        permissions: Set<Permission>,
    ) {
        jdbc
            .sql("update access_policy set permissions = :bits where id = :id")
            .param("id", id.value)
            .param("bits", permissions.sumOf { it.bit })
            .update()
    }

    fun deletePolicy(id: PolicyId) {
        jdbc.sql("delete from access_policy where id = :id").param("id", id.value).update()
    }

    // ---------- Team roles ----------

    private val json = JsonMapper.builder().build()
    private val labelsType = object : TypeReference<Map<String, String>>() {}

    private val roleMapper =
        RowMapper { rs, _ ->
            TeamRoleDefinition(
                role = TeamRole(rs.getString("key")),
                labels = json.readValue(rs.getString("labels"), labelsType),
                description = rs.getString("description"),
                system = rs.getBoolean("system"),
                active = rs.getBoolean("active"),
                sortOrder = rs.getInt("sort_order"),
            )
        }

    fun roles(tenant: TenantId): List<TeamRoleDefinition> =
        jdbc
            .sql("select * from team_role where tenant_id = :tenant order by sort_order, key")
            .param("tenant", tenant.value)
            .query(roleMapper)
            .list()

    fun role(
        tenant: TenantId,
        role: TeamRole,
    ): TeamRoleDefinition? =
        jdbc
            .sql("select * from team_role where tenant_id = :tenant and key = :key")
            .param("tenant", tenant.value)
            .param("key", role.key)
            .query(roleMapper)
            .optional()
            .orElse(null)

    fun insertRole(
        tenant: TenantId,
        definition: TeamRoleDefinition,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into team_role (tenant_id, key, labels, description, system, active, sort_order)
                    values (:tenant, :key, cast(:labels as jsonb), :description, false, :active, :sortOrder)
                    """,
                ).param("tenant", tenant.value)
                .param("key", definition.role.key)
                .param("labels", json.writeValueAsString(definition.labels))
                .param("description", definition.description)
                .param("active", definition.active)
                .param("sortOrder", definition.sortOrder)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_ROLE",
                "A role '${definition.role.key}' already exists",
                mapOf("role" to definition.role.key),
            ).apply { initCause(e) }
        }
    }

    fun updateRole(
        tenant: TenantId,
        definition: TeamRoleDefinition,
    ) {
        jdbc
            .sql(
                """
                update team_role set labels = cast(:labels as jsonb), description = :description,
                       active = :active, sort_order = :sortOrder
                where tenant_id = :tenant and key = :key
                """,
            ).param("tenant", tenant.value)
            .param("key", definition.role.key)
            .param("labels", json.writeValueAsString(definition.labels))
            .param("description", definition.description)
            .param("active", definition.active)
            .param("sortOrder", definition.sortOrder)
            .update()
    }
}
