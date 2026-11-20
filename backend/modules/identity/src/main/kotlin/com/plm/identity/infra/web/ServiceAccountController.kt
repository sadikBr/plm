package com.plm.identity.infra.web

import com.plm.identity.api.ApiToken
import com.plm.identity.api.CreateServiceAccount
import com.plm.identity.api.ServiceAccount
import com.plm.identity.api.ServiceAccountService
import com.plm.identity.api.TokenAuthenticator
import com.plm.identity.api.TokenKind
import com.plm.identity.api.UpdateServiceAccount
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@RestController
internal class ServiceAccountController(
    private val accounts: ServiceAccountService,
    private val authenticator: TokenAuthenticator,
) {
    @GetMapping("/api/v1/service-accounts")
    fun list(actor: Actor) = accounts.list(actor).map(::ServiceAccountResponse)

    @PostMapping("/api/v1/service-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: CreateServiceAccount,
    ) = ServiceAccountResponse(accounts.create(actor, request))

    @PutMapping("/api/v1/service-accounts/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: String,
        @RequestBody request: UpdateServiceAccount,
    ) = ServiceAccountResponse(accounts.update(actor, PrincipalId(id), request))

    /** Its API tokens and client secrets (never the secrets themselves). */
    @GetMapping("/api/v1/service-accounts/{id}/tokens")
    fun tokens(
        actor: Actor,
        @PathVariable id: String,
    ) = accounts.tokens(actor, PrincipalId(id)).map(::ApiTokenResponse)

    /** Issues an API token or a client secret. The response is the only time the secret is shown. */
    @PostMapping("/api/v1/service-accounts/{id}/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(
        actor: Actor,
        @PathVariable id: String,
        @RequestBody request: IssueTokenRequest,
    ): IssuedTokenResponse {
        val issued =
            accounts.issue(
                actor,
                PrincipalId(id),
                request.kind ?: TokenKind.API_TOKEN,
                request.name,
                request.scopes,
                request.expiresInDays?.let { Duration.ofDays(it.toLong()) },
            )
        return IssuedTokenResponse(ApiTokenResponse(issued.token), issued.secret)
    }

    @DeleteMapping("/api/v1/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        actor: Actor,
        @PathVariable id: UUID,
    ) = accounts.revoke(actor, id)

    /**
     * OAuth 2.0 client credentials (RFC 6749 §4.4): a service account's id and one of its client
     * secrets, as form fields or HTTP Basic, for an access token valid one hour. Open to unauthenticated
     * calls, like any token endpoint; errors follow RFC 6749 §5.2.
     */
    @PostMapping("/api/v1/oauth/token", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun token(
        @RequestParam("grant_type") grantType: String,
        @RequestParam("client_id", required = false) clientId: String?,
        @RequestParam("client_secret", required = false) clientSecret: String?,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<Any> {
        if (grantType != "client_credentials") {
            return ResponseEntity.badRequest().body(
                OAuthError("unsupported_grant_type", "Only client_credentials is supported"),
            )
        }
        val basic =
            authorization
                ?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
                ?.let {
                    runCatching {
                        Base64
                            .getDecoder()
                            .decode(
                                it.substring(6).trim(),
                            ).decodeToString()
                    }.getOrNull()
                }?.split(":", limit = 2)
        val id = basic?.getOrNull(0) ?: clientId
        val secret = basic?.getOrNull(1) ?: clientSecret
        val issued = if (id != null && secret != null) authenticator.exchange(id, secret) else null
        return if (issued == null) {
            ResponseEntity
                .status(
                    HttpStatus.UNAUTHORIZED,
                ).body(OAuthError("invalid_client", "Unknown client or secret"))
        } else {
            ResponseEntity.ok(
                OAuthToken(
                    issued.secret,
                    "Bearer",
                    Duration.between(issued.token.createdAt, issued.token.expiresAt).seconds,
                    issued.token.scopes.joinToString(" "),
                ),
            )
        }
    }
}

internal data class ServiceAccountResponse(
    val id: String,
    val username: String,
    val displayName: String,
    val description: String?,
    val siteAdmin: Boolean,
    val active: Boolean,
    val extensionId: String?,
    val createdAt: Instant,
    val createdBy: String,
) {
    constructor(a: ServiceAccount) :
        this(
            a.id.value,
            a.username,
            a.displayName,
            a.description,
            a.siteAdmin,
            a.active,
            a.extensionId,
            a.createdAt,
            a.createdBy,
        )
}

internal data class ApiTokenResponse(
    val id: UUID,
    val principalId: String,
    val kind: TokenKind,
    val name: String,
    val prefix: String,
    val scopes: List<String>,
    val extensionId: String?,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val createdAt: Instant,
    val createdBy: String,
    val revokedAt: Instant?,
) {
    constructor(t: ApiToken) :
        this(
            t.id,
            t.principalId.value,
            t.kind,
            t.name,
            t.prefix,
            t.scopes,
            t.extensionId,
            t.expiresAt,
            t.lastUsedAt,
            t.createdAt,
            t.createdBy,
            t.revokedAt,
        )
}

/** The token just issued, and its [secret]: shown this once, never again. */
internal data class IssuedTokenResponse(
    val token: ApiTokenResponse,
    val secret: String,
)

internal data class IssueTokenRequest(
    val name: String,
    val scopes: List<String>,
    /** API_TOKEN (default) or CLIENT_SECRET. */
    val kind: TokenKind? = null,
    /** Null: never expires. */
    val expiresInDays: Int? = null,
)

@Suppress("PropertyName", "ConstructorParameterNaming")
internal data class OAuthToken(
    val access_token: String,
    val token_type: String,
    val expires_in: Long,
    val scope: String,
)

@Suppress("PropertyName", "ConstructorParameterNaming")
internal data class OAuthError(
    val error: String,
    val error_description: String,
)
