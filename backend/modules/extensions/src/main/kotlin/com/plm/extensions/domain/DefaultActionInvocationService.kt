package com.plm.extensions.domain

import com.plm.extensions.api.ActionDownload
import com.plm.extensions.api.ActionForm
import com.plm.extensions.api.ActionHandler
import com.plm.extensions.api.ActionInvocation
import com.plm.extensions.api.ActionInvocationService
import com.plm.extensions.api.ActionResult
import com.plm.extensions.api.ActionService
import com.plm.extensions.api.ExtensionActionInvoked
import com.plm.extensions.infra.InvocationRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.util.Base64

private val log = LoggerFactory.getLogger(DefaultActionInvocationService::class.java)
private const val MAX_ANSWER = 5 * 1024 * 1024

/**
 * Runs extension actions on the server: a signed POST to the extension's service, then its answer
 * turned into an [ActionResult]. No database transaction is held while waiting for the service;
 * the log entry and the audit event are written afterwards, in their own.
 */
@Service
internal class DefaultActionInvocationService(
    private val actions: ActionService,
    private val extensionActions: ExtensionActions,
    private val versioning: VersioningService,
    private val invocations: InvocationRepository,
    private val events: DomainEvents,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : ActionInvocationService {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun invoke(
        actor: Actor,
        objectId: MasterId,
        actionId: String,
        parameters: Map<String, Any?>,
    ): ActionResult {
        // The same checks as the toolbar: permission, type, state, visibility condition.
        val available =
            actions.available(actor, objectId).firstOrNull { it.id == actionId }
                ?: throw NotFoundException(
                    "ACTION_NOT_FOUND",
                    "There's no action $actionId on this object",
                    mapOf(
                        "actionId" to actionId,
                    ),
                )
        if (available.handler != ActionHandler.WEBHOOK) {
            throw ValidationException("ACTION_NOT_INVOCABLE", "$actionId runs in the browser, not on the server")
        }
        if (!available.enabled) {
            throw ConflictException("ACTION_NOT_AVAILABLE", available.reason ?: "This action can't be used now")
        }
        val (row, action) =
            extensionActions.find(actor.tenant, actionId)
                ?: throw NotFoundException(
                    "ACTION_NOT_FOUND",
                    "There's no action $actionId",
                    mapOf(
                        "actionId" to actionId,
                    ),
                )
        val webhook = action.handler.webhook!!
        val answers = validate(action.form, parameters)

        val obj = versioning.get(actor, objectId)
        val model = versioning.expressionModel(actor, objectId)
        val invocationId = UuidV7.generate(clock)
        val timestamp = clock.instant().epochSecond.toString()

        @Suppress("UNCHECKED_CAST")
        val payload =
            mapOf(
                "invocationId" to invocationId.toString(),
                "action" to actionId,
                "extension" to row.manifest.id,
                "timestamp" to timestamp,
                "object" to (model["object"] as Map<String, Any?>) + ("id" to obj.id.value.toString()),
                "user" to model["user"],
                "parameters" to answers,
            )
        val body = Manifests.json.writeValueAsString(payload)
        val url = extensionActions.fill(extensionActions.fillConfig(webhook.url, row.config), obj, model, actor)
        val secret = row.secrets[webhook.secret].orEmpty()

        val started = System.nanoTime()
        var httpStatus: Int? = null
        val (status, result) =
            try {
                val request =
                    HttpRequest
                        .newBuilder(URI(url))
                        .timeout(Duration.ofSeconds(webhook.timeoutSeconds.toLong()))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "PLM-Extensions/1")
                        .header("X-PLM-Invocation", invocationId.toString())
                        .header("X-PLM-Action", actionId)
                        .header("X-PLM-Timestamp", timestamp)
                        .header("X-PLM-Signature", Signatures.sign(secret, timestamp, body))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                httpStatus = response.statusCode()
                answer(response.statusCode(), response.body())
            } catch (e: HttpTimeoutException) {
                "TIMEOUT" to failure("${row.manifest.name} didn't answer within ${webhook.timeoutSeconds} s")
            } catch (e: Exception) {
                log.info("Webhook {} of {} failed: {}", actionId, url, e.toString())
                "ERROR" to failure("${row.manifest.name} couldn't be reached (${e.javaClass.simpleName})")
            }
        val duration = (System.nanoTime() - started) / 1_000_000

        tx.executeWithoutResult {
            invocations.insert(
                actor.tenant,
                ActionInvocation(
                    invocationId,
                    actionId,
                    row.manifest.id,
                    obj.id.value,
                    obj.number,
                    actor.principal.value,
                    clock.instant(),
                    status,
                    httpStatus,
                    duration,
                    result.message,
                ),
            )
            events.publish(
                actor,
                ExtensionActionInvoked(obj.id.value, obj.number, actionId, row.manifest.id, status, result.message),
            )
        }
        return result
    }

    override fun invocations(
        actor: Actor,
        extensionId: String,
        limit: Int,
    ): List<ActionInvocation> {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators see extension logs")
        return invocations.latest(actor.tenant, extensionId, limit.coerceIn(1, 500))
    }

    /** The service's answer: 2xx with optional JSON instructions; anything else is a failure. */
    private fun answer(
        status: Int,
        bytes: ByteArray,
    ): Pair<String, ActionResult> {
        if (bytes.size > MAX_ANSWER) return "ERROR" to failure("The answer was too large")
        val node: JsonNode? =
            try {
                if (bytes.isEmpty()) null else Manifests.json.readTree(bytes)
            } catch (_: JacksonException) {
                null
            }
        val message =
            node
                ?.get("message")
                ?.takeIf { it.isString }
                ?.asString()
                ?.take(1000)
        if (status !in 200..299) {
            return "FAILED" to failure(message ?: "The service answered $status")
        }
        val level =
            node
                ?.get("level")
                ?.asString()
                ?.uppercase()
                ?.takeIf { it in setOf("INFO", "WARNING", "ERROR") } ?: "INFO"
        val navigate = node?.get("navigate")
        val download =
            node?.get("download")?.let { d ->
                val content = d.get("content")?.asString() ?: return@let null
                if (runCatching { Base64.getDecoder().decode(content) }.isFailure) return@let null
                ActionDownload(
                    filename =
                        d
                            .get("filename")
                            ?.asString()
                            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            ?.take(100) ?: "download",
                    contentType = d.get("contentType")?.asString() ?: "application/octet-stream",
                    content = content,
                )
            }
        return (if (level == "ERROR") "FAILED" else "OK") to
            ActionResult(
                ok = level != "ERROR",
                level = level,
                message = message ?: "Done",
                refresh = node?.get("refresh")?.asBoolean() == true,
                navigateToObject =
                    navigate?.get("objectId")?.asString()?.let {
                        runCatching { java.util.UUID.fromString(it) }.getOrNull()
                    },
                navigateToUrl =
                    navigate?.get("url")?.asString()?.takeIf {
                        it.startsWith("https://") ||
                            it.startsWith("http://")
                    },
                download = download,
            )
    }

    private fun failure(message: String) = ActionResult(ok = false, level = "ERROR", message = message)

    /** The form's answers, typed and checked; every problem at once (`INVALID_PARAMETERS`). */
    private fun validate(
        form: ActionForm?,
        given: Map<String, Any?>,
    ): Map<String, Any?> {
        val fields = form?.properties.orEmpty()
        val errors = mutableListOf<Map<String, String>>()
        given.keys.filter { it !in fields }.forEach { errors += mapOf("field" to it, "message" to "Unknown field") }
        val answers = linkedMapOf<String, Any?>()
        for ((name, f) in fields) {
            val label = f.title ?: name
            val raw = given[name]?.takeUnless { it is String && it.isBlank() } ?: f.default
            if (raw == null) {
                if (name in form!!.required) errors += mapOf("field" to name, "message" to "$label is required")
                continue
            }
            val value: Any? =
                when (f.type) {
                    "integer" ->
                        (raw as? Number)?.toLong()?.takeIf { raw.toDouble() % 1.0 == 0.0 }
                            ?: raw.toString().toLongOrNull()
                    "number" -> (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
                    "boolean" -> raw as? Boolean ?: raw.toString().toBooleanStrictOrNull()
                    else -> raw.toString()
                }
            val number = (value as? Number)?.toDouble()
            val problem =
                when {
                    value == null -> "$label must be ${if (f.type == "boolean") "yes or no" else "a ${f.type}"}"
                    f.enum.isNotEmpty() && value !in f.enum -> "$label must be one of ${f.enum.joinToString()}"
                    number != null && f.minimum != null && number < f.minimum -> "$label must be at least ${f.minimum}"
                    number != null && f.maximum != null && number > f.maximum -> "$label must be at most ${f.maximum}"
                    value is String && value.length > (f.maxLength ?: 2000) -> "$label is too long"
                    else -> null
                }
            if (problem != null) errors += mapOf("field" to name, "message" to problem) else answers[name] = value
        }
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_PARAMETERS",
                errors.joinToString("; ") { it.getValue("message") },
                mapOf(
                    "errors" to errors,
                ),
            )
        }
        return answers
    }
}
