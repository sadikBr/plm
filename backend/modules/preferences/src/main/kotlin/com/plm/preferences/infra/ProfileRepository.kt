package com.plm.preferences.infra

import com.plm.platform.id.TenantId
import com.plm.preferences.api.Profile
import com.plm.preferences.api.ProfileMember
import com.plm.preferences.api.ProfileMemberKind
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
internal class ProfileRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    fun all(tenant: TenantId): List<Profile> {
        val members =
            jdbc
                .sql("select * from ui_profile_member where tenant_id = :tenant order by member_kind, member_id")
                .param("tenant", tenant.value)
                .query { rs, _ ->
                    rs.getObject("profile_id", UUID::class.java) to
                        ProfileMember(ProfileMemberKind.valueOf(rs.getString("member_kind")), rs.getString("member_id"))
                }.list()
                .groupBy({ it.first }, { it.second })
        return jdbc
            .sql("select * from ui_profile where tenant_id = :tenant order by lower(name)")
            .param("tenant", tenant.value)
            .query { rs, _ -> map(rs, members) }
            .list()
    }

    fun find(
        tenant: TenantId,
        id: UUID,
    ): Profile? = all(tenant).firstOrNull { it.id == id }

    fun nameTaken(
        tenant: TenantId,
        name: String,
        except: UUID?,
    ): Boolean =
        jdbc
            .sql(
                "select count(*) from ui_profile where tenant_id = :tenant and lower(name) = lower(:name) and id <> :except",
            ).param("tenant", tenant.value)
            .param("name", name)
            .param("except", except ?: UUID(0, 0))
            .query(Int::class.java)
            .single() > 0

    fun save(
        tenant: TenantId,
        p: Profile,
        actor: String,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into ui_profile (tenant_id, id, name, description, hidden_actions, hidden_areas,
                                        created_at, created_by, updated_at, updated_by)
                values (:tenant, :id, :name, :description, cast(:actions as jsonb), cast(:areas as jsonb),
                        :now, :actor, :now, :actor)
                on conflict (id) do update set name = excluded.name, description = excluded.description,
                    hidden_actions = excluded.hidden_actions, hidden_areas = excluded.hidden_areas,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", p.id)
            .param("name", p.name)
            .param("description", p.description)
            .param("actions", json.writeValueAsString(p.hiddenActions))
            .param("areas", json.writeValueAsString(p.hiddenAreas))
            .param("now", Timestamp.from(now))
            .param("actor", actor)
            .update()
        jdbc.sql("delete from ui_profile_member where profile_id = :id").param("id", p.id).update()
        for (m in p.members) {
            jdbc
                .sql(
                    "insert into ui_profile_member (profile_id, tenant_id, member_kind, member_id) values (:id, :tenant, :kind, :member)",
                ).param("id", p.id)
                .param("tenant", tenant.value)
                .param("kind", m.kind.name)
                .param("member", m.id)
                .update()
        }
    }

    fun delete(
        tenant: TenantId,
        id: UUID,
    ) {
        jdbc
            .sql("delete from ui_profile where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .update()
    }

    /**
     * Profiles that apply to [principal]: as a member, or through a shared team it belongs to.
     * Reads the access module's shared team members.
     */
    fun of(
        tenant: TenantId,
        principal: String,
    ): List<Profile> {
        val ids =
            jdbc
                .sql(
                    """
                    select distinct m.profile_id from ui_profile_member m
                    where m.tenant_id = :tenant and (
                        (m.member_kind = 'USER' and m.member_id = :principal)
                        or (m.member_kind = 'SHARED_TEAM' and m.member_id in (
                            select cast(t.team_id as text) from shared_team_member t
                            where t.tenant_id = :tenant and t.principal_id = :principal)))
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("principal", principal)
                .query { rs, _ -> rs.getObject("profile_id", UUID::class.java) }
                .set()
        if (ids.isEmpty()) return emptyList()
        return all(tenant).filter { it.id in ids }
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(text: String?): List<String> =
        if (text ==
            null
        ) {
            emptyList()
        } else {
            json.readValue(text, List::class.java) as List<String>
        }

    private fun map(
        rs: ResultSet,
        members: Map<UUID, List<ProfileMember>>,
    ): Profile {
        val id = rs.getObject("id", UUID::class.java)
        return Profile(
            id = id,
            name = rs.getString("name"),
            description = rs.getString("description"),
            members = members[id].orEmpty(),
            hiddenActions = list(rs.getString("hidden_actions")),
            hiddenAreas = list(rs.getString("hidden_areas")),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            updatedBy = rs.getString("updated_by"),
        )
    }
}
