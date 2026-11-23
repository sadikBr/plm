package com.plm.identity.domain

import com.plm.identity.api.ApiToken
import com.plm.identity.api.IssuedToken
import com.plm.identity.api.PersonalTokenService
import com.plm.identity.api.TokenKind
import com.plm.identity.api.TokenScopes
import com.plm.identity.infra.PrincipalRepository
import com.plm.identity.infra.ServiceAccountRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** People's own access tokens (roadmap 8.3), for the desktop sync client and their scripts. */
@Service
@Transactional
internal class DefaultPersonalTokenService(
    private val accounts: ServiceAccountRepository,
    private val principals: PrincipalRepository,
    private val minter: DefaultServiceAccountService,
    private val clock: Clock,
) : PersonalTokenService {
    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<ApiToken> =
        accounts
            .tokens(actor.tenant, actor.principal)
            .filter { it.kind == TokenKind.PERSONAL && it.revokedAt == null }

    override fun issue(
        actor: Actor,
        name: String,
        scopes: List<String>,
        expiresIn: Duration?,
    ): IssuedToken {
        val person =
            principals.find(actor.tenant, actor.principal)
                ?: throw ValidationException("NOT_A_PERSON", "Only people have personal tokens")
        if (accounts.account(actor.tenant, actor.principal) != null) {
            throw ValidationException("NOT_A_PERSON", "Service accounts get their tokens from an administrator")
        }
        val n = name.trim()
        if (n.isEmpty() ||
            n.length > 100
        ) {
            throw ValidationException("INVALID_TOKEN_NAME", "Name the token (up to 100 characters)")
        }
        val allowed = if (person.siteAdmin) TokenScopes.ALL else listOf(TokenScopes.READ, TokenScopes.WRITE)
        if (scopes.isEmpty() || scopes.any { it !in allowed }) {
            throw ValidationException("INVALID_SCOPES", "Scopes are ${allowed.joinToString(", ")}")
        }
        return minter.mintPersonal(actor, n, scopes.distinct(), expiresIn)
    }

    override fun revoke(
        actor: Actor,
        tokenId: UUID,
    ) {
        val token = accounts.token(actor.tenant, tokenId)
        if (token == null || token.kind != TokenKind.PERSONAL || token.principalId != actor.principal) {
            throw NotFoundException("TOKEN_NOT_FOUND", "Token $tokenId does not exist")
        }
        accounts.revoke(tokenId, clock.instant())
    }
}
