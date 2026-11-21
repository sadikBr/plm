package com.plm.identity.infra

import com.plm.identity.api.Principal
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
internal class PrincipalRepository(
    private val jdbc: JdbcClient,
) {
    private val mapper =
        RowMapper { rs, _ ->
            Principal(
                id = PrincipalId(rs.getString("id")),
                username = rs.getString("username"),
                displayName = rs.getString("display_name"),
                email = rs.getString("email"),
                firstSeen = rs.getTimestamp("first_seen").toInstant(),
                lastSeen = rs.getTimestamp("last_seen").toInstant(),
                siteAdmin = rs.getBoolean("site_admin"),
            )
        }

    fun upsert(
        tenant: TenantId,
        id: PrincipalId,
        username: String,
        displayName: String,
        email: String?,
        siteAdmin: Boolean,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into principal (id, tenant_id, username, display_name, email, site_admin, first_seen, last_seen)
                values (:id, :tenant, :username, :displayName, :email, :siteAdmin, :now, :now)
                on conflict (tenant_id, id) do update
                    set username = excluded.username, display_name = excluded.display_name,
                        email = excluded.email, site_admin = excluded.site_admin, last_seen = excluded.last_seen
                """,
            ).param("id", id.value)
            .param("tenant", tenant.value)
            .param("username", username)
            .param("displayName", displayName)
            .param("email", email)
            .param("siteAdmin", siteAdmin)
            .param("now", Timestamp.from(now))
            .update()
    }

    fun find(
        tenant: TenantId,
        id: PrincipalId,
    ): Principal? =
        jdbc
            .sql("select * from principal where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .query(mapper)
            .optional()
            .orElse(null)

    fun findByUsername(
        tenant: TenantId,
        username: String,
    ): Principal? =
        jdbc
            .sql("select * from principal where tenant_id = :tenant and lower(username) = lower(:username)")
            .param("tenant", tenant.value)
            .param("username", username)
            .query(mapper)
            .optional()
            .orElse(null)

    /** Users (not service accounts) who held the site administrator role at their latest sign-in. */
    fun siteAdmins(tenant: TenantId): List<Principal> =
        jdbc
            .sql(
                """
                select p.* from principal p
                where p.tenant_id = :tenant and p.site_admin and p.kind = 'USER'
                order by lower(p.username)
                """,
            ).param("tenant", tenant.value)
            .query(mapper)
            .list()
}
