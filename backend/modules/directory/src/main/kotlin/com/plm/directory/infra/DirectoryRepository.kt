package com.plm.directory.infra

import com.plm.directory.api.DirectoryGroupMember
import com.plm.directory.api.DirectoryUser
import com.plm.platform.id.TenantId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A group's own row; members, team and contexts are added by the service. */
internal data class GroupRow(
    val id: UUID,
    val externalId: String?,
    val displayName: String,
    val role: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int,
)

internal class Duplicate(
    message: String,
) : RuntimeException(message)

@Repository
internal class DirectoryRepository(
    private val jdbc: JdbcClient,
) {
    private fun ResultSet.user() =
        DirectoryUser(
            id = getObject("id", UUID::class.java),
            principalId = getString("principal_id"),
            externalId = getString("external_id"),
            userName = getString("user_name"),
            displayName = getString("display_name"),
            givenName = getString("given_name"),
            familyName = getString("family_name"),
            email = getString("email"),
            active = getBoolean("active"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            version = getInt("version"),
        )

    private fun ResultSet.group() =
        GroupRow(
            id = getObject("id", UUID::class.java),
            externalId = getString("external_id"),
            displayName = getString("display_name"),
            role = getString("role"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            version = getInt("version"),
        )

    fun users(tenant: TenantId): List<DirectoryUser> =
        jdbc
            .sql("select * from scim_user where tenant_id = :tenant order by lower(user_name)")
            .param("tenant", tenant.value)
            .query { rs, _ -> rs.user() }
            .list()

    fun user(
        tenant: TenantId,
        id: UUID,
    ): DirectoryUser? =
        jdbc
            .sql("select * from scim_user where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query { rs, _ -> rs.user() }
            .optional()
            .orElse(null)

    fun saveUser(
        tenant: TenantId,
        user: DirectoryUser,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into scim_user (id, tenant_id, principal_id, external_id, user_name, display_name, given_name,
                                           family_name, email, active, created_at, updated_at, version)
                    values (:id, :tenant, :principal, :externalId, :userName, :displayName, :givenName,
                            :familyName, :email, :active, :createdAt, :updatedAt, :version)
                    on conflict (id) do update
                        set principal_id = excluded.principal_id, external_id = excluded.external_id,
                            user_name = excluded.user_name, display_name = excluded.display_name,
                            given_name = excluded.given_name, family_name = excluded.family_name,
                            email = excluded.email, active = excluded.active,
                            updated_at = excluded.updated_at, version = excluded.version
                    """,
                ).param("id", user.id)
                .param("tenant", tenant.value)
                .param("principal", user.principalId)
                .param("externalId", user.externalId)
                .param("userName", user.userName)
                .param("displayName", user.displayName)
                .param("givenName", user.givenName)
                .param("familyName", user.familyName)
                .param("email", user.email)
                .param("active", user.active)
                .param("createdAt", Timestamp.from(user.createdAt))
                .param("updatedAt", Timestamp.from(user.updatedAt))
                .param("version", user.version)
                .update()
        } catch (e: DuplicateKeyException) {
            throw Duplicate("A user named ${user.userName} already exists").apply { initCause(e) }
        }
    }

    fun deleteUser(id: UUID) {
        jdbc.sql("delete from scim_user where id = :id").param("id", id).update()
    }

    fun rekey(
        tenant: TenantId,
        from: String,
        to: String,
    ) {
        jdbc
            .sql("update scim_user set principal_id = :to where tenant_id = :tenant and principal_id = :from")
            .param("tenant", tenant.value)
            .param("from", from)
            .param("to", to)
            .update()
    }

    fun groups(tenant: TenantId): List<GroupRow> =
        jdbc
            .sql("select * from scim_group where tenant_id = :tenant order by lower(display_name)")
            .param("tenant", tenant.value)
            .query { rs, _ -> rs.group() }
            .list()

    fun group(
        tenant: TenantId,
        id: UUID,
    ): GroupRow? =
        jdbc
            .sql("select * from scim_group where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query { rs, _ -> rs.group() }
            .optional()
            .orElse(null)

    fun saveGroup(
        tenant: TenantId,
        group: GroupRow,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into scim_group (id, tenant_id, external_id, display_name, role, created_at, updated_at, version)
                    values (:id, :tenant, :externalId, :displayName, :role, :createdAt, :updatedAt, :version)
                    on conflict (id) do update
                        set external_id = excluded.external_id, display_name = excluded.display_name,
                            role = excluded.role, updated_at = excluded.updated_at, version = excluded.version
                    """,
                ).param("id", group.id)
                .param("tenant", tenant.value)
                .param("externalId", group.externalId)
                .param("displayName", group.displayName)
                .param("role", group.role)
                .param("createdAt", Timestamp.from(group.createdAt))
                .param("updatedAt", Timestamp.from(group.updatedAt))
                .param("version", group.version)
                .update()
        } catch (e: DuplicateKeyException) {
            throw Duplicate("A group named ${group.displayName} already exists").apply { initCause(e) }
        }
    }

    fun deleteGroup(id: UUID) {
        jdbc.sql("delete from scim_group where id = :id").param("id", id).update()
    }

    fun members(group: UUID): List<DirectoryGroupMember> =
        jdbc
            .sql(
                """
                select u.id, u.user_name from scim_group_member m join scim_user u on u.id = m.user_id
                where m.group_id = :group order by lower(u.user_name)
                """,
            ).param("group", group)
            .query { rs, _ -> DirectoryGroupMember(rs.getObject("id", UUID::class.java), rs.getString("user_name")) }
            .list()

    /** Active members' principal ids. */
    fun activePrincipals(group: UUID): List<String> =
        jdbc
            .sql(
                """
                select u.principal_id from scim_group_member m join scim_user u on u.id = m.user_id
                where m.group_id = :group and u.active
                """,
            ).param("group", group)
            .query { rs, _ -> rs.getString(1) }
            .list()

    fun groupsOf(user: UUID): List<UUID> =
        jdbc
            .sql("select group_id from scim_group_member where user_id = :user")
            .param("user", user)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) }
            .list()

    fun setMembers(
        group: UUID,
        users: Collection<UUID>,
    ) {
        jdbc.sql("delete from scim_group_member where group_id = :group").param("group", group).update()
        users.forEach {
            jdbc
                .sql(
                    """
                    insert into scim_group_member (group_id, user_id)
                    select :group, id from scim_user where id = :user on conflict do nothing
                    """,
                ).param("group", group)
                .param("user", it)
                .update()
        }
    }

    fun lastChange(tenant: TenantId): Instant? =
        jdbc
            .sql(
                """
                select max(t) from (select max(updated_at) t from scim_user where tenant_id = :tenant
                                    union all select max(updated_at) from scim_group where tenant_id = :tenant) x
                """,
            ).param("tenant", tenant.value)
            .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }
            .optional()
            .orElse(null)
}
