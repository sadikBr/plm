package com.plm.extensions.domain

import com.plm.extensions.infra.ExtensionRepository
import com.plm.platform.actor.Actor
import com.plm.platform.contract.PlatformContract
import com.plm.platform.error.NotFoundException
import com.plm.platform.id.TenantId
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID

private val log = LoggerFactory.getLogger(UiExtensions::class.java)
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

/** A panel or tab an extension adds to an object's page (roadmap 5.15). */
data class UiSlot(
    val extensionId: String,
    val extensionName: String,
    val id: String,
    /** `object.panel` or `object.tab`. */
    val slot: String,
    val title: String,
    val url: String,
    /** The origin the frame's page is served from (the bridge only talks to it). */
    val origin: String,
    val height: Int,
)

/** What an extension endpoint answered, relayed as is. */
internal data class ProxiedResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
)

@Service
internal class UiExtensions(
    private val extensions: ExtensionRepository,
    private val actions: ExtensionActions,
    private val versioning: VersioningService,
    private val clock: Clock,
) {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    private fun active(tenant: TenantId) =
        extensions
            .all(tenant)
            .filter { it.enabled && Versions.Range.parse(it.manifest.platform).includes(PlatformContract.VERSION) }

    /** The panels and tabs that apply to the object, for someone who can read it. */
    fun slots(
        actor: Actor,
        objectId: UUID,
    ): List<UiSlot> {
        val obj = versioning.get(actor, MasterId(objectId))
        val model = versioning.expressionModel(actor, obj.id)

        @Suppress("UNCHECKED_CAST")
        val lineage = ((model["object"] as? Map<String, Any?>)?.get("types") as? List<String>).orEmpty()
        return active(actor.tenant)
            .flatMap { row ->
                val m = Manifests.read(row.source) ?: row.manifest
                m.ui
                    .filter { u ->
                        (u.appliesTo.types.isEmpty() || u.appliesTo.types.any { it in lineage }) &&
                            (u.appliesTo.states.isEmpty() || obj.latest.state in u.appliesTo.states)
                    }.sortedBy { it.order }
                    .map { u ->
                        val url = actions.fill(actions.fillConfig(u.url, row.config), obj, model, actor)
                        val origin = URI(url).let { "${it.scheme}://${it.authority}" }
                        UiSlot(m.id, m.name, u.id, u.slot, u.title, url, origin, u.height)
                    }
            }
    }

    /**
     * Forwards a signed-in user's call to an extension endpoint: [path] is what follows
     * `/api/v1/ext/<extensionId>/`. Signed like webhooks; the service learns who is calling.
     */
    fun forward(
        actor: Actor,
        extensionId: String,
        method: String,
        path: String,
        query: String?,
        contentType: String?,
        body: ByteArray,
    ): ProxiedResponse {
        val row = active(actor.tenant).firstOrNull { it.manifest.id == extensionId } ?: throw notFound()
        val m = Manifests.read(row.source) ?: row.manifest
        val endpoint =
            m.endpoints
                .filter { path == it.path || path.startsWith(it.path + "/") }
                .maxByOrNull { it.path.length } ?: throw notFound()
        if (method !in endpoint.methods) {
            return ProxiedResponse(
                405,
                "application/json",
                """{"message":"$method isn't allowed here"}""".toByteArray(),
            )
        }
        val rest = path.removePrefix(endpoint.path)
        if (rest.split('/').any { it == ".." }) throw notFound()
        val target = actions.fillConfig(endpoint.url, row.config).trimEnd('/') + rest + (query?.let { "?$it" } ?: "")
        val timestamp = clock.instant().epochSecond.toString()
        val text = String(body)
        val request =
            HttpRequest
                .newBuilder(URI(target))
                .timeout(Duration.ofSeconds(endpoint.timeoutSeconds.toLong()))
                .header("Content-Type", contentType ?: "application/json")
                .header("X-PLM-Timestamp", timestamp)
                .header("X-PLM-Signature", Signatures.sign(row.secrets[endpoint.secret].orEmpty(), timestamp, text))
                .header("X-PLM-User", actor.principal.value)
                .header("X-PLM-Tenant", actor.tenant.value.toString())
                .header("X-PLM-Extension", m.id)
                .method(
                    method,
                    if (body.isEmpty()) {
                        HttpRequest.BodyPublishers.noBody()
                    } else {
                        HttpRequest.BodyPublishers.ofByteArray(
                            body,
                        )
                    },
                ).build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
            if (bytes.size > MAX_RESPONSE_BYTES) {
                ProxiedResponse(
                    502,
                    "application/json",
                    """{"message":"The extension's answer is too large"}""".toByteArray(),
                )
            } else {
                ProxiedResponse(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse("application/octet-stream"),
                    bytes,
                )
            }
        } catch (e: java.io.IOException) {
            log.warn("Extension endpoint {}/{} failed: {}", m.id, endpoint.id, e.toString())
            ProxiedResponse(502, "application/json", """{"message":"${m.name} didn't answer"}""".toByteArray())
        } catch (e: java.net.http.HttpTimeoutException) {
            ProxiedResponse(504, "application/json", """{"message":"${m.name} took too long"}""".toByteArray())
        }
    }

    private fun notFound() = NotFoundException("ENDPOINT_NOT_FOUND", "No such extension endpoint")
}
