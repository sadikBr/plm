package com.plm.app.web

import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns the validated bearer token into the [Actor] controllers receive.
 *
 * - principal = the token's `sub`: stable for the user's lifetime, unlike username or email.
 * - siteAdmin = the realm role [ADMIN_ROLE].
 * - tenant = the default tenant. Multi-tenant deployments will map a token claim here (ADR 0009).
 *
 * The user directory is refreshed at most every [REFRESH] per user, not on every request.
 */
@Component
internal class JwtActorResolver(
    private val directory: PrincipalDirectory,
    private val clock: Clock,
) : HandlerMethodArgumentResolver {
    private val lastRecorded = ConcurrentHashMap<String, Instant>()

    override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == Actor::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Actor {
        val authentication = SecurityContextHolder.getContext().authentication
        // An API token (service account, or a user's delegated to an extension): no profile to record.
        if (authentication is PlmTokenAuthenticationToken) {
            val d = authentication.details
            return Actor(tenant = d.tenant, principal = d.principal, siteAdmin = d.siteAdmin)
        }
        val auth =
            authentication as? JwtAuthenticationToken
                ?: error("Actor requested on an unauthenticated request; check SecurityConfiguration")
        val jwt = auth.token
        val subject = auth.name // the converter guarantees a non-blank `sub`
        val actor =
            Actor(
                tenant = TenantId.DEFAULT,
                principal = PrincipalId(subject),
                siteAdmin = auth.authorities.any { it.authority == "ROLE_$ADMIN_ROLE" },
            )
        val now = clock.instant()
        val previous = lastRecorded[subject]
        if (previous == null || previous.plus(REFRESH).isBefore(now)) {
            directory.recordSignIn(
                SignIn(
                    tenant = actor.tenant,
                    id = actor.principal,
                    username = jwt.getClaimAsString("preferred_username") ?: subject,
                    displayName = jwt.getClaimAsString("name"),
                    email = jwt.getClaimAsString("email"),
                ),
            )
            lastRecorded[subject] = now
        }
        return actor
    }

    companion object {
        const val ADMIN_ROLE = "plm-admin"
        private val REFRESH: Duration = Duration.ofMinutes(5)
    }
}
