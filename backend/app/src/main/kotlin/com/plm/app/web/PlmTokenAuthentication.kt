package com.plm.app.web

import com.plm.identity.api.TokenAuthentication
import com.plm.identity.api.TokenAuthenticator
import com.plm.identity.api.TokenScopes
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationManagerResolver
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider
import org.springframework.web.filter.OncePerRequestFilter

/** An API token of ours (`plm_…`): a service account's, or a user's delegated to an extension. */
internal class PlmTokenAuthenticationToken(
    val details: TokenAuthentication,
) : AbstractAuthenticationToken(
        if (details.siteAdmin) listOf(SimpleGrantedAuthority("ROLE_${JwtActorResolver.ADMIN_ROLE}")) else emptyList(),
    ) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = details.principal.value

    override fun getName(): String = details.principal.value
}

/**
 * Bearer tokens come in two kinds (roadmap 2.7): JWTs from the identity provider (people), and our
 * own API tokens (`plm_…`: service accounts, client-credential access tokens, delegated tokens).
 * Each goes to its own check.
 */
internal class BearerTokenRouter(
    jwtDecoder: JwtDecoder,
    private val tokens: TokenAuthenticator,
) : AuthenticationManagerResolver<HttpServletRequest> {
    private val jwt: AuthenticationManager =
        ProviderManager(
            JwtAuthenticationProvider(jwtDecoder).apply { setJwtAuthenticationConverter(KeycloakJwtConverter()) },
        )

    private val plm =
        AuthenticationManager { authentication: Authentication ->
            val token = (authentication as BearerTokenAuthenticationToken).token
            val details =
                tokens.authenticate(token)
                    ?: throw InvalidBearerTokenException("The API token is unknown, revoked or expired")
            PlmTokenAuthenticationToken(details)
        }

    override fun resolve(request: HttpServletRequest): AuthenticationManager {
        val header = request.getHeader("Authorization").orEmpty()
        return if (header.startsWith("Bearer plm_", ignoreCase = true)) plm else jwt
    }
}

/**
 * What an API token may do (its scopes), on top of the access rules of its principal: `read` for
 * GET requests, `write` for changes. Tokens never manage credentials (service accounts, tokens) or
 * extensions: those need a person.
 */
internal class TokenScopeFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val auth = SecurityContextHolder.getContext().authentication as? PlmTokenAuthenticationToken
        if (auth != null) {
            val path = request.requestURI
            val reading = request.method in setOf("GET", "HEAD", "OPTIONS")
            val scopes = auth.details.scopes
            val problem =
                when {
                    CREDENTIALS.any {
                        path.startsWith(
                            it,
                        )
                    } -> "API tokens can't manage service accounts, tokens or extensions"
                    reading && TokenScopes.READ !in scopes && TokenScopes.WRITE !in scopes ->
                        "This token can't read: it needs the read scope"
                    !reading && TokenScopes.WRITE !in scopes ->
                        "This token can't make changes: it needs the write scope"
                    else -> null
                }
            if (problem != null) {
                response.status = 403
                response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                response.writer.write(
                    """{"type":"about:blank","title":"Forbidden","status":403,"code":"INSUFFICIENT_SCOPE","detail":"$problem"}""",
                )
                return
            }
        }
        chain.doFilter(request, response)
    }

    private companion object {
        val CREDENTIALS = listOf("/api/v1/service-accounts", "/api/v1/tokens", "/api/v1/extensions")
    }
}
