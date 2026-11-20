package com.plm.access.infra

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextTemplate
import com.plm.access.api.ContextTemplateId
import com.plm.access.api.Permission
import com.plm.access.api.PolicyEffect
import com.plm.access.api.SharedTeam
import com.plm.access.api.SharedTeamId
import com.plm.access.api.SharedTeamMember
import com.plm.access.api.TeamRole
import com.plm.access.api.TemplateContent
import com.plm.access.api.TemplateMember
import com.plm.access.api.TemplatePolicy
import com.plm.platform.error.ConflictException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
internal class TemplateRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    // ---------- Shared teams ----------

    private fun members(team: SharedTeamId): List<SharedTeamMember> =
        jdbc
            .sql("select * from shared_team_member where team_id = :team order by principal_id")
            .param("team", team.value)
            .query { rs, _ ->
                SharedTeamMember(
                    PrincipalId(rs.getString("principal_id")),
                    TeamRole(rs.getString("role")),
                    rs.getTimestamp("added_at").toInstant(),
                    rs.getString("added_by"),
                )
            }.list()

    private fun ResultSet.team() =
        SharedTeam(
            id = SharedTeamId(getObject("id", UUID::class.java)),
            name = getString("name"),
            description = getString("description"),
            members = members(SharedTeamId(getObject("id", UUID::class.java))),
            createdAt = getTimestamp("created_at").toInstant(),
            createdBy = getString("created_by"),
        )

    fun teams(tenant: TenantId): List<SharedTeam> =
        jdbc
            .sql("select * from shared_team where tenant_id = :tenant order by name")
            .param("tenant", tenant.value)
            .query { rs, _ -> rs.team() }
            .list()

    fun team(
        tenant: TenantId,
        id: SharedTeamId,
    ): SharedTeam? =
        jdbc
            .sql("select * from shared_team where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .query { rs, _ -> rs.team() }
            .optional()
            .orElse(null)

    fun insertTeam(
        tenant: TenantId,
        team: SharedTeam,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into shared_team (id, tenant_id, name, description, created_at, created_by)
                    values (:id, :tenant, :name, :description, :createdAt, :createdBy)
                    """,
                ).param("id", team.id.value)
                .param("tenant", tenant.value)
                .param("name", team.name)
                .param("description", team.description)
                .param("createdAt", Timestamp.from(team.createdAt))
                .param("createdBy", team.createdBy)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("DUPLICATE_SHARED_TEAM", "A shared team named '${team.name}' already exists")
                .apply { initCause(e) }
        }
    }

    fun deleteTeam(id: SharedTeamId) {
        jdbc.sql("delete from shared_team where id = :id").param("id", id.value).update()
    }

    fun putTeamMember(
        tenant: TenantId,
        team: SharedTeamId,
        member: SharedTeamMember,
    ) {
        jdbc
            .sql(
                """
                insert into shared_team_member (team_id, tenant_id, principal_id, role, added_at, added_by)
                values (:team, :tenant, :principal, :role, :addedAt, :addedBy)
                on conflict (team_id, principal_id) do update
                    set role = excluded.role, added_at = excluded.added_at, added_by = excluded.added_by
                """,
            ).param("team", team.value)
            .param("tenant", tenant.value)
            .param("principal", member.principalId.value)
            .param("role", member.role.key)
            .param("addedAt", Timestamp.from(member.addedAt))
            .param("addedBy", member.addedBy)
            .update()
    }

    fun removeTeamMember(
        team: SharedTeamId,
        principal: PrincipalId,
    ): Int =
        jdbc
            .sql("delete from shared_team_member where team_id = :team and principal_id = :principal")
            .param("team", team.value)
            .param("principal", principal.value)
            .update()

    fun attachedTeams(context: ContextId): List<SharedTeamId> =
        jdbc
            .sql("select team_id from context_shared_team where context_id = :context order by attached_at")
            .param("context", context.value)
            .query { rs, _ -> SharedTeamId(rs.getObject("team_id", UUID::class.java)) }
            .list()

    fun attach(
        tenant: TenantId,
        context: ContextId,
        team: SharedTeamId,
        at: Instant,
        by: String,
    ) {
        jdbc
            .sql(
                """
                insert into context_shared_team (context_id, team_id, tenant_id, attached_at, attached_by)
                values (:context, :team, :tenant, :at, :by)
                on conflict do nothing
                """,
            ).param("context", context.value)
            .param("team", team.value)
            .param("tenant", tenant.value)
            .param("at", Timestamp.from(at))
            .param("by", by)
            .update()
    }

    fun detach(
        context: ContextId,
        team: SharedTeamId,
    ): Int =
        jdbc
            .sql("delete from context_shared_team where context_id = :context and team_id = :team")
            .param("context", context.value)
            .param("team", team.value)
            .update()

    // ---------- Templates ----------

    @Suppress("UNCHECKED_CAST")
    private fun content(raw: String): TemplateContent {
        val map = json.readValue(raw, mapType)
        val policies =
            (map["policies"] as? List<Map<String, Any?>>).orEmpty().map { p ->
                TemplatePolicy(
                    role = p["role"] as String,
                    type = p["type"] as String?,
                    state = p["state"] as String?,
                    effect = PolicyEffect.valueOf(p["effect"] as String),
                    permissions = (p["permissions"] as List<String>).map(Permission::valueOf).toSet(),
                )
            }
        val members =
            (map["members"] as? List<Map<String, Any?>>).orEmpty().map { m ->
                TemplateMember(m["principalId"] as String, m["role"] as String)
            }
        return TemplateContent(policies, members, (map["sharedTeams"] as? List<String>).orEmpty())
    }

    private fun write(content: TemplateContent): String =
        json.writeValueAsString(
            mapOf(
                "policies" to
                    content.policies.map {
                        mapOf(
                            "role" to it.role,
                            "type" to it.type,
                            "state" to it.state,
                            "effect" to it.effect.name,
                            "permissions" to it.permissions.sortedBy(Permission::bit).map(Permission::name),
                        )
                    },
                "members" to content.members.map { mapOf("principalId" to it.principalId, "role" to it.role) },
                "sharedTeams" to content.sharedTeams,
            ),
        )

    private fun ResultSet.template() =
        ContextTemplate(
            id = ContextTemplateId(getObject("id", UUID::class.java)),
            name = getString("name"),
            kind = ContextKind.valueOf(getString("kind")),
            description = getString("description"),
            content = content(getString("content")),
            createdAt = getTimestamp("created_at").toInstant(),
            createdBy = getString("created_by"),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    fun templates(tenant: TenantId): List<ContextTemplate> =
        jdbc
            .sql("select * from context_template where tenant_id = :tenant order by kind, name")
            .param("tenant", tenant.value)
            .query { rs, _ -> rs.template() }
            .list()

    fun template(
        tenant: TenantId,
        id: ContextTemplateId,
    ): ContextTemplate? =
        jdbc
            .sql("select * from context_template where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .query { rs, _ -> rs.template() }
            .optional()
            .orElse(null)

    fun saveTemplate(
        tenant: TenantId,
        template: ContextTemplate,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into context_template (id, tenant_id, name, kind, description, content, created_at, created_by, updated_at)
                    values (:id, :tenant, :name, :kind, :description, cast(:content as jsonb), :createdAt, :createdBy, :updatedAt)
                    on conflict (id) do update set name = excluded.name, kind = excluded.kind,
                        description = excluded.description, content = excluded.content, updated_at = excluded.updated_at
                    """,
                ).param("id", template.id.value)
                .param("tenant", tenant.value)
                .param("name", template.name)
                .param("kind", template.kind.name)
                .param("description", template.description)
                .param("content", write(template.content))
                .param("createdAt", Timestamp.from(template.createdAt))
                .param("createdBy", template.createdBy)
                .param("updatedAt", Timestamp.from(template.updatedAt))
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_TEMPLATE",
                "A ${template.kind.name.lowercase()} template named '${template.name}' already exists",
            ).apply { initCause(e) }
        }
    }

    fun deleteTemplate(id: ContextTemplateId) {
        jdbc.sql("delete from context_template where id = :id").param("id", id.value).update()
    }
}
