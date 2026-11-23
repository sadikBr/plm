package com.plm.app.web

import com.plm.identity.api.TokenAuthenticator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * The API is an OAuth2 resource server (ADR 0010, roadmap 0.8). Every request under `/api` needs a
 * valid bearer token from the configured issuer (Keycloak in dev), with our audience.
 * Health probes stay open for orchestrators.
 */
@Configuration(proxyBeanMethods = false)
internal class SecurityConfiguration(
    private val actorResolver: JwtActorResolver,
) : WebMvcConfigurer {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        tokens: TokenAuthenticator,
    ): SecurityFilterChain =
        http
            .authorizeHttpRequests {
                it
                    .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info")
                    .permitAll()
                    // The API contract and its browser are public; calling the API still needs a token.
                    .requestMatchers("/v3/api-docs/**", "/v3/api-docs", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    // OAuth client credentials: the client authenticates with its secret, not a token.
                    .requestMatchers("/api/v1/oauth/token")
                    .permitAll()
                    // Vault links are signed and short-lived: the URL is the permission (roadmap 3.2).
                    .requestMatchers(HttpMethod.PUT, "/api/v1/vault/uploads/*", "/api/v1/vault/uploads/*/parts/*")
                    .permitAll()
                    // Resumable uploads (roadmap 9.10): signed like the upload itself.
                    .requestMatchers(HttpMethod.GET, "/api/v1/vault/uploads/*/parts")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/vault/uploads/*/complete")
                    .permitAll()
                    // A background action's service reports back with the job's own token (roadmap 3.11).
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/extension-jobs/*/progress",
                        "/api/v1/extension-jobs/*/complete",
                    ).permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/vault/files")
                    .permitAll()
                    // A delivered package (roadmap 9.4): the link is the key; it expires and is logged.
                    .requestMatchers(HttpMethod.GET, "/api/v1/deliveries/*/download")
                    .permitAll()
                    // An ERP fetching and acknowledging distribution messages (roadmap 9.5): the target's secret.
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/distribution/targets/*/fetch",
                        "/api/v1/distribution/messages/*/ack",
                    ).permitAll()
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { rs -> rs.authenticationManagerResolver(BearerTokenRouter(jwtDecoder, tokens)) }
            .addFilterAfter(TokenScopeFilter(), BearerTokenAuthenticationFilter::class.java)
            // Stateless bearer-token API: no sessions, so no CSRF exposure.
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            // File previews (roadmap 3.2) are framed by our own pages.
            .headers { h -> h.frameOptions { it.sameOrigin() } }
            .build()

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(actorResolver)
    }
}

/** Maps Keycloak realm roles (`realm_access.roles`) to `ROLE_<name>` authorities. */
internal class KeycloakJwtConverter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        // Every user is identified by `sub`; a token without one is not ours to accept.
        val subject =
            jwt.subject?.takeIf { it.isNotBlank() } ?: throw InvalidBearerTokenException("Token has no subject")
        val realmAccess = jwt.getClaimAsMap("realm_access").orEmpty()
        val roles = (realmAccess["roles"] as? Collection<*>).orEmpty().map { it.toString() }
        val authorities: List<GrantedAuthority> = roles.map { SimpleGrantedAuthority("ROLE_$it") }
        return JwtAuthenticationToken(jwt, authorities, subject)
    }
}
