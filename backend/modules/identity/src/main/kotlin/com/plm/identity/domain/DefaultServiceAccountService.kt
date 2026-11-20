package com.plm.identity.domain

import com.plm.identity.api.ApiToken
import com.plm.identity.api.CreateServiceAccount
import com.plm.identity.api.IssuedToken
import com.plm.identity.api.ServiceAccount
import com.plm.identity.api.ServiceAccountChanged
import com.plm.identity.api.ServiceAccountService
import com.plm.identity.api.TokenAuthentication
import com.plm.identity.api.TokenAuthenticator
import com.plm.identity.api.TokenKind
import com.plm.identity.api.TokenScopes
import com.plm.identity.api.UpdateServiceAccount
import com.plm.identity.infra.ServiceAccountRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

private val NAME = Regex("^[a-z][a-z0-9-]{1,40}$")
private val ACCESS_TTL: Duration = Duration.ofHours(1)
private val MAX_DELEGATED_TTL: Duration = Duration.ofMinutes(15)
private val USE_RECORDING: Duration = Duration.ofMinutes(1)

/**
 * Service accounts and tokens (roadmap 2.7). Tokens are `plm_` and 43 random characters; only
 * their SHA-256 is stored, so a leaked database doesn't leak working tokens.
 */
@Service
@Transactional
internal class DefaultServiceAccountService(
    private val accounts: ServiceAccountRepository,
    private val events: DomainEvents,
    private val clock: Clock,
) : ServiceAccountService,
    TokenAuthenticator {
    private val random = SecureRandom()

    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<ServiceAccount> {
        requireAdmin(actor)
        return accounts.accounts(actor.tenant)
    }

    override fun create(
        actor: Actor,
        command: CreateServiceAccount,
    ): ServiceAccount {
        requireAdmin(actor)
        val name = command.name.trim()
        if (!NAME.matches(
                name,
            )
        ) {
            throw ValidationException(
                "INVALID_SERVICE_ACCOUNT",
                "The name must be lower-case letters, digits and dashes",
            )
        }
        return insert(actor, name, command.displayName, command.description, command.siteAdmin, null)
    }

    override fun update(
        actor: Actor,
        id: PrincipalId,
        command: UpdateServiceAccount,
    ): ServiceAccount {
        requireAdmin(actor)
        val current = find(actor, id)
        val displayName = command.displayName.trim()
        if (displayName.isEmpty() ||
            displayName.length > 100
        ) {
            throw ValidationException("INVALID_SERVICE_ACCOUNT", "A display name is required")
        }
        val updated =
            current.copy(
                displayName = displayName,
                description = command.description?.trim()?.ifEmpty { null },
                siteAdmin = command.siteAdmin,
                active = command.active,
            )
        accounts.update(actor.tenant, updated)
        events.publish(actor, ServiceAccountChanged(id.value, "UPDATED"))
        return updated
    }

    @Transactional(readOnly = true)
    override fun tokens(
        actor: Actor,
        id: PrincipalId,
    ): List<ApiToken> {
        requireAdmin(actor)
        find(actor, id)
        return accounts.tokens(actor.tenant, id)
    }

    override fun issue(
        actor: Actor,
        id: PrincipalId,
        kind: TokenKind,
        name: String,
        scopes: List<String>,
        expiresIn: Duration?,
    ): IssuedToken {
        requireAdmin(actor)
        val account = find(actor, id)
        if (kind != TokenKind.API_TOKEN && kind != TokenKind.CLIENT_SECRET) {
            throw ValidationException("INVALID_TOKEN_REQUEST", "Only API tokens and client secrets are issued here")
        }
        val label = name.trim()
        if (label.isEmpty() ||
            label.length > 80
        ) {
            throw ValidationException("INVALID_TOKEN_REQUEST", "Give the token a name, to recognize it later")
        }
        val unknown = scopes.filter { it !in TokenScopes.ALL }
        if (unknown.isNotEmpty() || scopes.isEmpty()) {
            throw ValidationException(
                "INVALID_TOKEN_REQUEST",
                "Scopes must be some of ${TokenScopes.ALL.joinToString()}",
            )
        }
        if (TokenScopes.ADMIN in scopes && !account.siteAdmin) {
            throw ValidationException(
                "INVALID_TOKEN_REQUEST",
                "Only accounts allowed to administer can have admin tokens",
            )
        }
        if (expiresIn != null && (expiresIn.isNegative || expiresIn > Duration.ofDays(730))) {
            throw ValidationException("INVALID_TOKEN_REQUEST", "Tokens expire within two years")
        }
        val issued =
            newToken(
                actor.tenant,
                id,
                kind,
                label,
                scopes.distinct(),
                account.extensionId,
                expiresIn,
                actor.principal.value,
            )
        events.publish(actor, ServiceAccountChanged(id.value, "TOKEN_ISSUED", label, issued.token.scopes))
        return issued
    }

    override fun revoke(
        actor: Actor,
        tokenId: UUID,
    ) {
        requireAdmin(actor)
        val token =
            accounts.token(actor.tenant, tokenId)
                ?: throw NotFoundException(
                    "TOKEN_NOT_FOUND",
                    "Token $tokenId does not exist",
                    mapOf(
                        "tokenId" to tokenId,
                    ),
                )
        accounts.revoke(tokenId, clock.instant())
        events.publish(actor, ServiceAccountChanged(token.principalId.value, "TOKEN_REVOKED", token.name))
    }

    override fun forExtension(
        actor: Actor,
        extensionId: String,
        displayName: String,
    ): ServiceAccount =
        accounts.accountForExtension(actor.tenant, extensionId)
            ?: insert(
                actor,
                "ext-" + extensionId.replace('.', '-').take(36),
                displayName,
                "The account the $extensionId extension acts as",
                false,
                extensionId,
            )

    override fun setExtensionActive(
        actor: Actor,
        extensionId: String,
        active: Boolean,
    ) {
        val account = accounts.accountForExtension(actor.tenant, extensionId) ?: return
        if (account.active == active) return
        accounts.update(actor.tenant, account.copy(active = active))
        events.publish(actor, ServiceAccountChanged(account.id.value, "UPDATED"))
    }

    // ---------- Checking tokens ----------

    override fun authenticate(token: String): TokenAuthentication? {
        if (!token.startsWith(PREFIX)) return null
        val stored = accounts.byHash(hash(token)) ?: return null
        val t = stored.token
        val now = clock.instant()
        if (t.revokedAt != null || t.kind == TokenKind.CLIENT_SECRET) return null
        if (t.expiresAt != null && !t.expiresAt.isAfter(now)) return null
        val account = if (t.kind == TokenKind.DELEGATED) null else accounts.account(stored.tenant, t.principalId)
        if (t.kind != TokenKind.DELEGATED && account?.active != true) return null
        if (t.lastUsedAt == null || t.lastUsedAt.plus(USE_RECORDING).isBefore(now)) accounts.used(t.id, now)
        return TokenAuthentication(
            tenant = stored.tenant,
            principal = t.principalId,
            // A delegated token is never an administrator, whoever the user is.
            siteAdmin = account?.siteAdmin == true && TokenScopes.ADMIN in t.scopes,
            scopes = t.scopes.toSet(),
            kind = t.kind,
            extensionId = t.extensionId,
        )
    }

    override fun exchange(
        clientId: String,
        clientSecret: String,
    ): IssuedToken? {
        val stored = accounts.byHash(hash(clientSecret)) ?: return null
        val secret = stored.token
        val now = clock.instant()
        if (secret.kind != TokenKind.CLIENT_SECRET ||
            secret.revokedAt != null ||
            secret.principalId.value != clientId
        ) {
            return null
        }
        if (secret.expiresAt != null && !secret.expiresAt.isAfter(now)) return null
        if (accounts.account(stored.tenant, secret.principalId)?.active != true) return null
        accounts.used(secret.id, now)
        return newToken(
            stored.tenant,
            secret.principalId,
            TokenKind.ACCESS,
            "Access token (${secret.name})",
            secret.scopes,
            secret.extensionId,
            ACCESS_TTL,
            clientId,
        )
    }

    override fun delegate(
        user: Actor,
        extensionId: String,
        scopes: List<String>,
        ttl: Duration,
    ): IssuedToken =
        newToken(
            user.tenant,
            user.principal,
            TokenKind.DELEGATED,
            "Delegated to $extensionId",
            scopes.filter { it != TokenScopes.ADMIN },
            extensionId,
            minOf(ttl, MAX_DELEGATED_TTL),
            user.principal.value,
        )

    /** Removes tokens that expired over a day ago; run by the `identity.purge-tokens` job. */
    fun purge(): Int = accounts.purgeExpired(clock.instant().minus(Duration.ofDays(1)))

    // ---------- Helpers ----------

    private fun insert(
        actor: Actor,
        name: String,
        displayName: String,
        description: String?,
        siteAdmin: Boolean,
        extensionId: String?,
    ): ServiceAccount {
        val label = displayName.trim()
        if (label.isEmpty() ||
            label.length > 100
        ) {
            throw ValidationException("INVALID_SERVICE_ACCOUNT", "A display name is required")
        }
        val account =
            ServiceAccount(
                id = PrincipalId("svc-" + UuidV7.generate(clock)),
                username = "svc-$name",
                displayName = label,
                description = description?.trim()?.ifEmpty { null },
                siteAdmin = siteAdmin,
                active = true,
                extensionId = extensionId,
                createdAt = clock.instant(),
                createdBy = actor.principal.value,
            )
        accounts.insert(actor.tenant, account)
        events.publish(actor, ServiceAccountChanged(account.id.value, "CREATED"))
        return account
    }

    private fun newToken(
        tenant: TenantId,
        principal: PrincipalId,
        kind: TokenKind,
        name: String,
        scopes: List<String>,
        extensionId: String?,
        expiresIn: Duration?,
        createdBy: String,
    ): IssuedToken {
        val bytes = ByteArray(32).also(random::nextBytes)
        val secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = clock.instant()
        val token =
            ApiToken(
                id = UuidV7.generate(clock),
                principalId = principal,
                kind = kind,
                name = name,
                prefix = secret.take(12),
                scopes = scopes,
                extensionId = extensionId,
                expiresAt = expiresIn?.let { now.plus(it) },
                lastUsedAt = null,
                createdAt = now,
                createdBy = createdBy,
                revokedAt = null,
            )
        accounts.insertToken(tenant, token, hash(secret))
        return IssuedToken(token, secret)
    }

    private fun find(
        actor: Actor,
        id: PrincipalId,
    ) = accounts.account(actor.tenant, id)
        ?: throw NotFoundException(
            "SERVICE_ACCOUNT_NOT_FOUND",
            "Service account $id does not exist",
            mapOf(
                "id" to id.value,
            ),
        )

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators manage service accounts",
            )
        }
    }

    companion object {
        const val PREFIX = "plm_"

        fun hash(token: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    }
}
