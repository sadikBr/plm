package com.plm.identity.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What a token may do, on top of the access rules of its principal. */
object TokenScopes {
    /** GET requests. */
    const val READ = "read"

    /** Changes too (create, check in, set state...). */
    const val WRITE = "write"

    /** Administration, for service accounts allowed to administer. */
    const val ADMIN = "admin"

    val ALL = listOf(READ, WRITE, ADMIN)
}

enum class TokenKind {
    /** A long-lived bearer token for a service account. */
    API_TOKEN,

    /** Exchanged at `POST /api/v1/oauth/token` (client credentials) for an [ACCESS] token. */
    CLIENT_SECRET,

    /** A short-lived bearer token from the token endpoint. */
    ACCESS,

    /** A short-lived token acting as a user, handed to an extension's service in a webhook call. */
    DELEGATED,
}

data class ServiceAccount(
    val id: PrincipalId,
    val username: String,
    val displayName: String,
    val description: String?,
    /** Its tokens may carry the `admin` scope. */
    val siteAdmin: Boolean,
    val active: Boolean,
    val extensionId: String?,
    val createdAt: Instant,
    val createdBy: String,
)

data class CreateServiceAccount(
    /** Lower-case letters, digits and dashes; becomes `svc-<name>` as username. */
    val name: String,
    val displayName: String,
    val description: String? = null,
    val siteAdmin: Boolean = false,
)

data class UpdateServiceAccount(
    val displayName: String,
    val description: String? = null,
    val siteAdmin: Boolean = false,
    val active: Boolean = true,
)

data class ApiToken(
    val id: UUID,
    val principalId: PrincipalId,
    val kind: TokenKind,
    val name: String,
    /** The token's first characters, to recognize it (`plm_ab12cd34`). */
    val prefix: String,
    val scopes: List<String>,
    val extensionId: String?,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val createdAt: Instant,
    val createdBy: String,
    val revokedAt: Instant?,
)

/** A token just issued: [secret] is shown this once and never again. */
data class IssuedToken(
    val token: ApiToken,
    val secret: String,
)

/** Who a valid token stands for. */
data class TokenAuthentication(
    val tenant: TenantId,
    val principal: PrincipalId,
    val siteAdmin: Boolean,
    val scopes: Set<String>,
    val kind: TokenKind,
    /** For tokens an extension uses (its account's, or delegated to it). */
    val extensionId: String?,
)

/**
 * Service accounts and their tokens (roadmap 2.7). Site administrators only (`ADMIN_REQUIRED`).
 * Error codes: `SERVICE_ACCOUNT_NOT_FOUND`, `INVALID_SERVICE_ACCOUNT`, `DUPLICATE_USERNAME`,
 * `INVALID_TOKEN_REQUEST`, `TOKEN_NOT_FOUND`.
 */
interface ServiceAccountService {
    fun list(actor: Actor): List<ServiceAccount>

    fun create(
        actor: Actor,
        command: CreateServiceAccount,
    ): ServiceAccount

    fun update(
        actor: Actor,
        id: PrincipalId,
        command: UpdateServiceAccount,
    ): ServiceAccount

    /** Its tokens, newest first (never the tokens themselves). */
    fun tokens(
        actor: Actor,
        id: PrincipalId,
    ): List<ApiToken>

    /** An [TokenKind.API_TOKEN] or a [TokenKind.CLIENT_SECRET], with [scopes] (`admin` only for site-admin accounts). */
    fun issue(
        actor: Actor,
        id: PrincipalId,
        kind: TokenKind,
        name: String,
        scopes: List<String>,
        expiresIn: Duration?,
    ): IssuedToken

    fun revoke(
        actor: Actor,
        tokenId: UUID,
    )

    /** The account an extension acts as; created on its first install. */
    fun forExtension(
        actor: Actor,
        extensionId: String,
        displayName: String,
    ): ServiceAccount

    /** Turns an extension's account off (uninstalled, disabled) or back on; its tokens follow. */
    fun setExtensionActive(
        actor: Actor,
        extensionId: String,
        active: Boolean,
    )
}

/** Checks tokens for the web layer, and issues the short-lived ones. */
interface TokenAuthenticator {
    /** Null when the token is unknown, revoked, expired, or its account is inactive. */
    fun authenticate(token: String): TokenAuthentication?

    /** Client credentials: a [TokenKind.CLIENT_SECRET] for an [TokenKind.ACCESS] token, or null. */
    fun exchange(
        clientId: String,
        clientSecret: String,
    ): IssuedToken?

    /**
     * A token acting as [user] (their access rules, their name in the audit trail), never as an
     * administrator, limited to [scopes], for [extensionId], valid for [ttl].
     */
    fun delegate(
        user: Actor,
        extensionId: String,
        scopes: List<String>,
        ttl: Duration,
    ): IssuedToken
}

data class ServiceAccountChanged(
    val principalId: String,
    /** CREATED, UPDATED, TOKEN_ISSUED, TOKEN_REVOKED */
    val change: String,
    val tokenName: String? = null,
    val scopes: List<String> = emptyList(),
) : DomainEvent {
    override val eventType = "identity.ServiceAccountChanged.v1"
    override val aggregateType = "service-account"
    override val aggregateId: UUID = UUID.nameUUIDFromBytes("service-account:$principalId".toByteArray())
}
