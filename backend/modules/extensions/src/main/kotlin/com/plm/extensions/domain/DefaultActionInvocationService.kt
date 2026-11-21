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
import com.plm.identity.api.TokenAuthenticator
import com.plm.identity.api.TokenScopes
import com.plm.jobs.api.JobCancelledException
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobService
import com.plm.jobs.api.SubmitJob
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
import org.springframework.beans.factory.annotation.Value
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val log = LoggerFactory.getLogger(DefaultActionInvocationService::class.java)
private const val MAX_ANSWER = 5 * 1024 * 1024
private val DELEGATION_TTL = Duration.ofMinutes(15)
private val HEARTBEAT = Duration.ofSeconds(15)

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
    private val tokens: TokenAuthenticator,
    private val jobs: JobService,
    private val background: BackgroundActions,
    @param:Value("\${plm.public-url:http://localhost:8080}") private val publicUrl: String,
) : ActionInvocationService {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    /**
     * With `api:read` or `api:write`, the call carries a token acting as the person who ran the
     * action (their access rules; never administration), so the service can read or change more
     * than the object it was given: `api.token`, valid until `api.expiresAt`, at `api.baseUrl`.
     */
    private fun delegation(
        actor: Actor,
        extensionId: String,
        permissions: List<String>,
    ): Map<String, Any?> {
        val scopes =
            buildList {
                if ("api:read" in permissions || "api:write" in permissions) add(TokenScopes.READ)
                if ("api:write" in permissions) add(TokenScopes.WRITE)
            }
        if (scopes.isEmpty()) return emptyMap()
        val issued = tokens.delegate(actor, extensionId, scopes, DELEGATION_TTL)
        return mapOf(
            "api" to
                mapOf(
                    "baseUrl" to publicUrl.trimEnd('/'),
                    "token" to issued.secret,
                    "expiresAt" to issued.token.expiresAt.toString(),
                    "scopes" to issued.token.scopes,
                ),
        )
    }

    private class Prepared(
        val label: String,
        val row: com.plm.extensions.infra.ExtensionRow,
        val webhook: com.plm.extensions.api.WebhookHandler,
        val answers: Map<String, Any?>,
    )

    /** The same checks as the toolbar: permission, type, state, visibility condition; then the form. */
    private fun prepare(
        actor: Actor,
        objectId: MasterId,
        actionId: String,
        parameters: Map<String, Any?>,
    ): Prepared {
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
        return Prepared(available.label, row, action.handler.webhook!!, validate(action.form, parameters))
    }

    override fun invoke(
        actor: Actor,
        objectId: MasterId,
        actionId: String,
        parameters: Map<String, Any?>,
    ): ActionResult {
        val prepared = prepare(actor, objectId, actionId, parameters)
        if (prepared.webhook.background) {
            val obj = versioning.get(actor, objectId)
            val job =
                jobs.submit(
                    actor,
                    SubmitJob(
                        ActionJob.TYPE,
                        mapOf(
                            "objectId" to obj.id.value.toString(),
                            "actionId" to actionId,
                            "parameters" to prepared.answers,
                        ),
                        "${prepared.label}: ${obj.number} ${obj.name}",
                    ),
                )
            return ActionResult(
                ok = true,
                level = "INFO",
                message = "Started in the background. You'll be notified when it's done.",
                jobId = job.id,
            )
        }
        return execute(actor, objectId, actionId, prepared, null)
    }

    /** For [ActionJob]: the checks again (access may have changed since), then the call, waiting as long as it may. */
    fun runInBackground(context: JobContext): ActionResult {
        val objectId = MasterId(java.util.UUID.fromString(context.payload["objectId"].toString()))
        val actionId = context.payload["actionId"].toString()

        @Suppress("UNCHECKED_CAST")
        val parameters = context.payload["parameters"] as? Map<String, Any?> ?: emptyMap()
        val prepared = prepare(context.actor, objectId, actionId, parameters)
        return execute(context.actor, objectId, actionId, prepared, context)
    }

    private fun execute(
        actor: Actor,
        objectId: MasterId,
        actionId: String,
        prepared: Prepared,
        job: JobContext?,
    ): ActionResult {
        val row = prepared.row
        val webhook = prepared.webhook
        val obj = versioning.get(actor, objectId)
        val model = versioning.expressionModel(actor, objectId)
        val invocationId = UuidV7.generate(clock)
        val timestamp = clock.instant().epochSecond.toString()
        val registered = job?.let { background.start(it) }

        @Suppress("UNCHECKED_CAST")
        val payload =
            mapOf(
                "invocationId" to invocationId.toString(),
                "action" to actionId,
                "extension" to row.manifest.id,
                "timestamp" to timestamp,
                "object" to (model["object"] as Map<String, Any?>) + ("id" to obj.id.value.toString()),
                "user" to model["user"],
                "parameters" to prepared.answers,
            )
        val jobInfo =
            if (job == null || registered == null) {
                emptyMap()
            } else {
                val base = publicUrl.trimEnd('/') + "/api/v1/extension-jobs/" + job.jobId
                mapOf(
                    "job" to
                        mapOf(
                            "id" to job.jobId.toString(),
                            "token" to registered.first,
                            "progressUrl" to "$base/progress",
                            "completeUrl" to "$base/complete",
                        ),
                )
            }
        val body =
            Manifests.json.writeValueAsString(
                payload + jobInfo + delegation(actor, row.manifest.id, row.manifest.permissions),
            )
        val url = extensionActions.fill(extensionActions.fillConfig(webhook.url, row.config), obj, model, actor)
        val secret = row.secrets[webhook.secret].orEmpty()

        val started = System.nanoTime()
        var httpStatus: Int? = null
        var cancelled: JobCancelledException? = null
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
                if (job == null || registered == null) {
                    val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    httpStatus = response.statusCode()
                    answer(response.statusCode(), response.body())
                } else {
                    val (code, bytes) =
                        callInBackground(
                            request,
                            Duration.ofSeconds(webhook.timeoutSeconds.toLong()),
                            job,
                            registered.second,
                        )
                    httpStatus = code
                    answer(code, bytes)
                }
            } catch (e: JobCancelledException) {
                cancelled = e
                "CANCELLED" to failure("Cancelled")
            } catch (e: HttpTimeoutException) {
                "TIMEOUT" to failure("${row.manifest.name} didn't answer within ${webhook.timeoutSeconds} s")
            } catch (e: Exception) {
                log.info("Webhook {} of {} failed: {}", actionId, url, e.toString())
                "ERROR" to failure("${row.manifest.name} couldn't be reached (${e.javaClass.simpleName})")
            } finally {
                job?.let { background.end(it.jobId) }
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
        cancelled?.let { throw it }
        return result
    }

    /**
     * Waits for a background action's answer: the HTTP answer, or (after a 202) the one posted to
     * `completeUrl`. Every 15 s it renews the job's lease with the latest progress, and stops if the
     * job was cancelled.
     */
    private fun callInBackground(
        request: HttpRequest,
        timeout: Duration,
        context: JobContext,
        running: BackgroundActions.Running,
    ): Pair<Int, ByteArray> {
        val deadline = System.nanoTime() + timeout.toNanos()
        val call = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
        try {
            val response = await(call, deadline, context, running)
            if (response.statusCode() != 202) return response.statusCode() to response.body()
            context.log("The service accepted it and works on it")
            return 200 to await(running.completion, deadline, context, running)
        } finally {
            call.cancel(true)
        }
    }

    private fun <T : Any> await(
        future: CompletableFuture<T>,
        deadline: Long,
        context: JobContext,
        running: BackgroundActions.Running,
    ): T {
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) throw HttpTimeoutException("No answer in time")
            try {
                return future.get(minOf(left, HEARTBEAT.toNanos()), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                val p = running.progress
                context.progress(p?.first ?: 0, p?.second, p?.third)
                context.checkCancelled()
            } catch (e: ExecutionException) {
                throw (e.cause as? Exception) ?: e
            }
        }
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
