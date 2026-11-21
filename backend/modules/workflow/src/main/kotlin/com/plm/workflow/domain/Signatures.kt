package com.plm.workflow.domain

import com.plm.platform.actor.Actor
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.ESignature
import com.plm.workflow.api.SignatureService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger(KeycloakPasswordVerifier::class.java)

/** Checks a signer's password again, at the moment of signing (roadmap 5.6). */
fun interface PasswordVerifier {
    fun verify(
        username: String,
        password: String,
    ): Boolean
}

/**
 * Asks the identity provider (Keycloak) whether the password is right, with a password grant on
 * the web client. Without an issuer configured, nobody can sign.
 */
@Component
internal class KeycloakPasswordVerifier(
    @param:Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") private val issuer: String,
    @param:Value("\${plm.signature.client-id:plm-web}") private val clientId: String,
) : PasswordVerifier {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun verify(
        username: String,
        password: String,
    ): Boolean {
        if (issuer.isBlank() || password.isEmpty()) return false

        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        val body = "grant_type=password&client_id=${enc(
            clientId,
        )}&username=${enc(username)}&password=${enc(password)}&scope=openid"
        return try {
            val response =
                http.send(
                    HttpRequest
                        .newBuilder(URI.create("${issuer.trimEnd('/')}/protocol/openid-connect/token"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            response.statusCode() == 200
        } catch (e: Exception) {
            log.warn("Couldn't check a signature with the identity provider: {}", e.toString())
            false
        }
    }
}

internal data class SignatureFacts(
    val tenant: TenantId,
    val principal: String,
    val username: String,
    val displayName: String,
    val meaning: String,
    val comment: String?,
    val signedAt: Instant,
    val objectId: UUID?,
    val objectNumber: String?,
    val objectVersion: String?,
    val iterationId: UUID?,
    val workItemId: UUID,
    val instanceId: UUID,
) {
    /** SHA-256 over every field: a changed record no longer matches. */
    fun hash(id: UUID): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                listOf(
                    id,
                    tenant.value,
                    principal,
                    username,
                    displayName,
                    meaning,
                    comment,
                    signedAt.toEpochMilli(),
                    objectId,
                    objectNumber,
                    objectVersion,
                    iterationId,
                    workItemId,
                    instanceId,
                ).joinToString("|") { it?.toString() ?: "" }
                    .toByteArray(),
            ).joinToString("") { "%02x".format(it) }
}

@Service
@Transactional
internal class DefaultSignatureService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
) : SignatureService {
    fun record(facts: SignatureFacts): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                """
                insert into e_signature (id, tenant_id, principal, username, display_name, meaning, comment, signed_at,
                    object_id, object_number, object_version, iteration_id, work_item_id, instance_id, hash)
                values (:id, :tenant, :principal, :username, :name, :meaning, :comment, :at, :object, :number, :version,
                    :iteration, :item, :instance, :hash)
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", facts.tenant.value)
            .param("principal", facts.principal)
            .param("username", facts.username)
            .param("name", facts.displayName)
            .param("meaning", facts.meaning)
            .param("comment", facts.comment)
            .param("at", Timestamp.from(facts.signedAt))
            .param("object", facts.objectId)
            .param("number", facts.objectNumber)
            .param("version", facts.objectVersion)
            .param("iteration", facts.iterationId)
            .param("item", facts.workItemId)
            .param("instance", facts.instanceId)
            .param("hash", facts.hash(id))
            .update()
        return id
    }

    @Transactional(readOnly = true)
    override fun forObject(
        actor: Actor,
        objectId: UUID,
    ): List<ESignature> {
        versioning.get(actor, MasterId(objectId))
        return jdbc
            .sql("select * from e_signature where tenant_id = :tenant and object_id = :id order by signed_at")
            .param("tenant", actor.tenant.value)
            .param("id", objectId)
            .query { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                val facts =
                    SignatureFacts(
                        TenantId(rs.getObject("tenant_id", UUID::class.java)),
                        rs.getString("principal"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("meaning"),
                        rs.getString("comment"),
                        rs.getTimestamp("signed_at").toInstant(),
                        rs.getObject("object_id", UUID::class.java),
                        rs.getString("object_number"),
                        rs.getString("object_version"),
                        rs.getObject("iteration_id", UUID::class.java),
                        rs.getObject("work_item_id", UUID::class.java),
                        rs.getObject("instance_id", UUID::class.java),
                    )
                ESignature(
                    id = id,
                    principal = facts.principal,
                    username = facts.username,
                    displayName = facts.displayName,
                    meaning = facts.meaning,
                    comment = facts.comment,
                    signedAt = facts.signedAt,
                    objectId = facts.objectId,
                    objectNumber = facts.objectNumber,
                    objectVersion = facts.objectVersion,
                    workItemId = facts.workItemId,
                    instanceId = facts.instanceId,
                    intact = facts.hash(id) == rs.getString("hash"),
                )
            }.list()
    }
}
