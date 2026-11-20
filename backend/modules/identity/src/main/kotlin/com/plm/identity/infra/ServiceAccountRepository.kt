package com.plm.identity.infra

import com.plm.identity.api.ApiToken
import com.plm.identity.api.ServiceAccount
import com.plm.identity.api.TokenKind
import com.plm.platform.error.ConflictException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A stored token, with what's needed to check it. */
internal data class StoredToken(
    val tenant: TenantId,
    val token: ApiToken,
)

@Repository
internal class ServiceAccountRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    private val accountMapper =
        RowMapper { rs, _ ->
            ServiceAccount(
                id = PrincipalId(rs.getString("id")),
                username = rs.getString("username"),
                displayName = rs.getString("display_name"),
                description = rs.getString("description"),
                siteAdmin = rs.getBoolean("site_admin"),
                active = rs.getBoolean("active"),
                extensionId = rs.getString("extension_id"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                createdBy = rs.getString("created_by"),
            )
        }

    private val accountSql =
        """
        select s.*, p.username, p.display_name from service_account s
        join principal p on p.tenant_id = s.tenant_id and p.id = s.id
        """.trimIndent()

    fun accounts(tenant: TenantId): List<ServiceAccount> =
        jdbc
            .sql("$accountSql where s.tenant_id = :tenant order by p.display_name")
            .param("tenant", tenant.value)
            .query(accountMapper)
            .list()

    fun account(
        tenant: TenantId,
        id: PrincipalId,
    ): ServiceAccount? =
        jdbc
            .sql("$accountSql where s.tenant_id = :tenant and s.id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .query(accountMapper)
            .optional()
            .orElse(null)

    fun accountForExtension(
        tenant: TenantId,
        extensionId: String,
    ): ServiceAccount? =
        jdbc
            .sql("$accountSql where s.tenant_id = :tenant and s.extension_id = :ext")
            .param("tenant", tenant.value)
            .param("ext", extensionId)
            .query(accountMapper)
            .optional()
            .orElse(null)

    fun insert(
        tenant: TenantId,
        a: ServiceAccount,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into principal (id, tenant_id, username, display_name, email, first_seen, last_seen, kind)
                    values (:id, :tenant, :username, :name, null, :at, :at, 'SERVICE')
                    """.trimIndent(),
                ).param("id", a.id.value)
                .param("tenant", tenant.value)
                .param("username", a.username)
                .param("name", a.displayName)
                .param("at", Timestamp.from(a.createdAt))
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_USERNAME",
                "There's already a user or service account named ${a.username}",
            )
        }
        jdbc
            .sql(
                """
                insert into service_account (tenant_id, id, description, site_admin, active, extension_id, created_at, created_by)
                values (:tenant, :id, :description, :admin, :active, :ext, :at, :by)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", a.id.value)
            .param("description", a.description)
            .param("admin", a.siteAdmin)
            .param("active", a.active)
            .param("ext", a.extensionId)
            .param("at", Timestamp.from(a.createdAt))
            .param("by", a.createdBy)
            .update()
    }

    fun update(
        tenant: TenantId,
        a: ServiceAccount,
    ) {
        jdbc
            .sql("update principal set display_name = :name where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", a.id.value)
            .param("name", a.displayName)
            .update()
        jdbc
            .sql(
                "update service_account set description = :d, site_admin = :admin, active = :active where tenant_id = :tenant and id = :id",
            ).param("tenant", tenant.value)
            .param("id", a.id.value)
            .param("d", a.description)
            .param("admin", a.siteAdmin)
            .param("active", a.active)
            .update()
    }

    // ---------- Tokens ----------

    private val tokenMapper =
        RowMapper { rs, _ ->
            StoredToken(
                TenantId(rs.getObject("tenant_id", UUID::class.java)),
                ApiToken(
                    id = rs.getObject("id", UUID::class.java),
                    principalId = PrincipalId(rs.getString("principal_id")),
                    kind = TokenKind.valueOf(rs.getString("kind")),
                    name = rs.getString("name"),
                    prefix = rs.getString("prefix"),
                    scopes = json.readValue(rs.getString("scopes"), Array<String>::class.java).toList(),
                    extensionId = rs.getString("extension_id"),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                    lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    createdBy = rs.getString("created_by"),
                    revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                ),
            )
        }

    fun insertToken(
        tenant: TenantId,
        t: ApiToken,
        hash: String,
    ) {
        jdbc
            .sql(
                """
                insert into api_token (id, tenant_id, principal_id, kind, name, token_hash, prefix, scopes, extension_id,
                    expires_at, created_at, created_by)
                values (:id, :tenant, :principal, :kind, :name, :hash, :prefix, cast(:scopes as jsonb), :ext, :expires, :at, :by)
                """.trimIndent(),
            ).param("id", t.id)
            .param("tenant", tenant.value)
            .param("principal", t.principalId.value)
            .param("kind", t.kind.name)
            .param("name", t.name)
            .param("hash", hash)
            .param("prefix", t.prefix)
            .param("scopes", json.writeValueAsString(t.scopes))
            .param("ext", t.extensionId)
            .param("expires", t.expiresAt?.let(Timestamp::from))
            .param("at", Timestamp.from(t.createdAt))
            .param("by", t.createdBy)
            .update()
    }

    fun byHash(hash: String): StoredToken? =
        jdbc
            .sql("select * from api_token where token_hash = :hash")
            .param("hash", hash)
            .query(tokenMapper)
            .optional()
            .orElse(null)

    /** The tokens of a principal an administrator manages (not the short-lived ones it was issued). */
    fun tokens(
        tenant: TenantId,
        principal: PrincipalId,
    ): List<ApiToken> =
        jdbc
            .sql(
                """
                select * from api_token where tenant_id = :tenant and principal_id = :p and kind in ('API_TOKEN', 'CLIENT_SECRET')
                order by created_at desc
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("p", principal.value)
            .query(tokenMapper)
            .list()
            .map { it.token }

    fun token(
        tenant: TenantId,
        id: UUID,
    ): ApiToken? =
        jdbc
            .sql("select * from api_token where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query(tokenMapper)
            .optional()
            .orElse(null)
            ?.token

    fun revoke(
        id: UUID,
        at: Instant,
    ) {
        jdbc
            .sql("update api_token set revoked_at = :at where id = :id and revoked_at is null")
            .param("id", id)
            .param("at", Timestamp.from(at))
            .update()
    }

    fun used(
        id: UUID,
        at: Instant,
    ) {
        jdbc
            .sql("update api_token set last_used_at = :at where id = :id")
            .param("id", id)
            .param("at", Timestamp.from(at))
            .update()
    }

    /** Short-lived tokens past their time are of no use: remove them. */
    fun purgeExpired(before: Instant) {
        jdbc
            .sql("delete from api_token where kind in ('ACCESS', 'DELEGATED') and expires_at < :before")
            .param("before", Timestamp.from(before))
            .update()
    }
}
