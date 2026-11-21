package com.plm.app.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Mints RS256 tokens shaped like Keycloak's, signed by a key generated for the test run.
 * The app validates them with the same rules as production: signature, expiry, audience.
 */
object TestTokens {
    const val AUDIENCE = "plm-api"
    private val trusted: KeyPair = rsa()
    private val stranger: KeyPair = rsa()

    data class User(
        val sub: String,
        val username: String,
        val name: String,
        val roles: List<String> = listOf("plm-user"),
    )

    val alice = User("a11ce000-0000-4000-8000-000000000001", "alice", "Alice Admin", listOf("plm-admin", "plm-user"))
    private val ADMIN_ROLES = listOf("plm-admin", "plm-user")
    val plmadmin = User("9a0d0000-0000-4000-8000-000000000000", "plmadmin", "Site Administrator", ADMIN_ROLES)
    val bob = User("b0b00000-0000-4000-8000-000000000002", "bob", "Bob Builder")
    val carol = User("ca201000-0000-4000-8000-000000000003", "carol", "Carol Contractor")

    fun token(
        user: User,
        audience: String = AUDIENCE,
        expiresAt: Instant = Instant.now().plusSeconds(600),
        signedByStranger: Boolean = false,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(user.sub)
                .issuer("https://test-issuer")
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date())
                .expirationTime(Date.from(expiresAt))
                .claim("preferred_username", user.username)
                .claim("name", user.name)
                .claim("email", "${user.username}@example.com")
                .claim("realm_access", mapOf("roles" to user.roles))
                .build()
        val key = if (signedByStranger) stranger else trusted
        return SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims)
            .apply { sign(RSASSASigner(key.private as RSAPrivateKey)) }
            .serialize()
    }

    fun decoder(): JwtDecoder =
        NimbusJwtDecoder.withPublicKey(trusted.public as RSAPublicKey).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtValidators.createDefault(),
                    JwtClaimValidator<List<String>>("aud") { AUDIENCE in it },
                ),
            )
        }

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}

/** Replaces the issuer-discovered decoder, so tests don't need a running Keycloak. */
@TestConfiguration(proxyBeanMethods = false)
class TestJwtConfiguration {
    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = TestTokens.decoder()
}
