package com.plm.automation.domain

import com.plm.automation.api.Automation
import com.plm.automation.api.AutomationAction
import com.plm.automation.api.AutomationActionKind
import com.plm.automation.api.AutomationRun
import com.plm.automation.api.AutomationStep
import com.plm.extensions.api.ServerScriptService
import com.plm.identity.api.PrincipalDirectory
import com.plm.notifications.api.PersonalService
import com.plm.platform.actor.Actor
import com.plm.platform.events.EventEnvelope
import com.plm.platform.expression.Expressions
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.ProcessService
import com.plm.workflow.api.StartProcess
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val log = LoggerFactory.getLogger(AutomationEngine::class.java)
private val PLACEHOLDER = Regex("\\$\\{(object|event)\\.([A-Za-z0-9_]+)}")

/** Thrown by a step that can't do its work: the run stops there. */
private class StepFailed(
    message: String,
) : RuntimeException(message)

/**
 * Runs automations (roadmap 5.16): on each event, after its transaction commits, the matching
 * automations run in the background, each step in its own transaction, as the `automation`
 * principal. Events caused by automations are ignored, so automations can't loop.
 */
@Component
internal class AutomationEngine(
    private val automations: DefaultAutomationService,
    private val versioning: VersioningService,
    private val processes: ProcessService,
    private val personal: PersonalService,
    private val scripts: ServerScriptService,
    private val directory: PrincipalDirectory,
    transactions: PlatformTransactionManager,
    private val clock: Clock,
    @param:Value("\${plm.ui-url:http://localhost:5173}") private val uiUrl: String,
) : org.springframework.beans.factory.DisposableBean {
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "automations").apply { isDaemon = true } }
    private val inNew =
        TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun destroy() {
        executor.shutdownNow()
    }

    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.actor == AUTOMATION_ACTOR) return
        val tenant = TenantId(envelope.tenantId)
        val matching = automations.all(tenant).filter { it.active && it.event == envelope.eventType }
        if (matching.isEmpty()) return
        val work =
            Runnable {
                matching.forEach { a ->
                    runCatching { run(tenant, a, envelope) }.onFailure { log.warn("Automation {} failed", a.name, it) }
                }
            }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        executor.submit(work)
                    }
                },
            )
        } else {
            executor.submit(work)
        }
    }

    private fun objectIdOf(envelope: EventEnvelope): UUID? =
        (envelope.payload["objectId"] ?: envelope.payload["subjectId"])?.toString()?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
            ?: envelope.aggregateId.takeIf { envelope.aggregateType == "object" }

    private fun run(
        tenant: TenantId,
        automation: Automation,
        envelope: EventEnvelope,
    ) {
        val started = System.nanoTime()
        val system = Actor(tenant, PrincipalId(AUTOMATION_ACTOR), siteAdmin = true)
        val objectId = objectIdOf(envelope)
        val obj = objectId?.let { find(system, it) }
        val model = obj?.let { inNew.execute { _ -> versioning.expressionModel(system, it.id) } }.orEmpty()

        @Suppress("UNCHECKED_CAST")
        val lineage = ((model["object"] as? Map<String, Any?>)?.get("types") as? List<String>).orEmpty()
        if (automation.types.isNotEmpty() && automation.types.none { it in lineage }) return
        val steps = mutableListOf<AutomationStep>()
        val condition = automation.condition
        if (!condition.isNullOrBlank()) {
            val holds =
                try {
                    Expressions.test(condition, model + ("event" to envelope.payload))
                } catch (e: RuntimeException) {
                    steps +=
                        AutomationStep(
                            AutomationActionKind.NOTIFY,
                            false,
                            "The condition couldn't be checked: ${e.message}",
                        )
                    finish(tenant, automation, envelope, obj, objectId, steps, started)
                    return
                }
            if (!holds) return
        }
        for (action in automation.actions) {
            val step =
                try {
                    AutomationStep(
                        action.kind,
                        true,
                        inNew.execute { _ -> act(system, automation, action, envelope, objectId) },
                    )
                } catch (e: StepFailed) {
                    AutomationStep(action.kind, false, e.message ?: "Failed")
                } catch (e: RuntimeException) {
                    AutomationStep(action.kind, false, e.message ?: e.javaClass.simpleName)
                }
            steps += step
            if (!step.ok) break
        }
        finish(tenant, automation, envelope, obj, objectId, steps, started)
    }

    private fun finish(
        tenant: TenantId,
        automation: Automation,
        envelope: EventEnvelope,
        obj: VersionedObject?,
        objectId: UUID?,
        steps: List<AutomationStep>,
        started: Long,
    ) {
        val run =
            AutomationRun(
                UuidV7.generate(clock),
                automation.id,
                clock.instant(),
                envelope.eventType,
                objectId,
                obj?.number ?: envelope.payload["number"]?.toString(),
                if (steps.all { it.ok }) "SUCCEEDED" else "FAILED",
                steps,
                ((System.nanoTime() - started) / 1_000_000).toInt(),
            )
        inNew.executeWithoutResult { automations.record(tenant, run, envelope.id) }
    }

    private fun find(
        system: Actor,
        id: UUID,
    ): VersionedObject? =
        inNew.execute { _ ->
            versioning.list(system, ObjectQuery(ids = listOf(MasterId(id)), limit = 1)).firstOrNull()
        }

    private fun fill(
        text: String,
        obj: VersionedObject?,
        envelope: EventEnvelope,
    ): String =
        PLACEHOLDER.replace(text) { m ->
            val key = m.groupValues[2]
            when (m.groupValues[1]) {
                "object" ->
                    when (key) {
                        "number" -> obj?.number
                        "name" -> obj?.name
                        "state" -> obj?.latest?.state
                        "version" -> obj?.latest?.version
                        else ->
                            obj
                                ?.latest
                                ?.attributes
                                ?.get(key)
                                ?.toString()
                    }
                else -> envelope.payload[key]?.toString()
            } ?: ""
        }

    private fun view(o: VersionedObject?) =
        o?.let {
            mapOf(
                "id" to it.id.value.toString(),
                "number" to it.number,
                "name" to it.name,
                "typeId" to it.typeId.value.toString(),
                "state" to it.latest.state,
                "version" to it.latest.version,
                "owner" to it.owner,
                "attributes" to it.latest.attributes,
            )
        }

    /** One step, in its own transaction; returns what it did. */
    private fun act(
        system: Actor,
        automation: Automation,
        a: AutomationAction,
        envelope: EventEnvelope,
        objectId: UUID?,
    ): String {
        val obj =
            objectId?.let {
                versioning
                    .list(
                        system,
                        ObjectQuery(ids = listOf(MasterId(it)), limit = 1),
                    ).firstOrNull()
            }

        fun needObject(): VersionedObject = obj ?: throw StepFailed("This event has no object to act on")
        val why = "Automation “${automation.name}”"
        return when (a.kind) {
            AutomationActionKind.SET_STATE -> {
                val o = needObject()
                if (o.latest.state == a.state) return "${o.number} is already ${a.state}"
                val r = versioning.setState(system, o.id, SetState(a.state!!, why))
                "${r.number} is now ${r.latest.state}"
            }
            AutomationActionKind.SET_ATTRIBUTE -> {
                val o = needObject()
                if (o.checkedOutBy != null) throw StepFailed("${o.number} is checked out by ${o.checkedOutBy}")
                val copy = versioning.checkOut(system, o.id)
                versioning.updateWorkingCopy(system, o.id, copy.attributes + (a.attribute!! to a.value))
                val r = versioning.checkIn(system, o.id, why)
                "${a.attribute} set on ${r.number} (${r.latest.version})"
            }
            AutomationActionKind.NOTIFY -> {
                val people =
                    a.recipients
                        .mapNotNull { r ->
                            when {
                                r == "owner" -> obj?.owner
                                r == "actor" -> envelope.actor.takeIf { it != "system" }
                                r.startsWith(
                                    "user:",
                                ) -> directory.findByUsername(system, r.removePrefix("user:"))?.id?.value
                                else -> null
                            }
                        }.distinct()
                if (people.isEmpty()) throw StepFailed("Nobody to tell")
                val message = fill(a.message!!, obj, envelope)
                people.forEach {
                    personal.notify(
                        system.tenant,
                        it,
                        message,
                        null,
                        obj?.let { o -> "$uiUrl/objects/${o.id.value}" },
                        obj?.id?.value,
                        AUTOMATION_ACTOR,
                    )
                }
                "Told ${people.size} ${if (people.size == 1) "person" else "people"}: $message"
            }
            AutomationActionKind.START_PROCESS -> {
                val o = needObject()
                val p = processes.start(system, StartProcess(a.templateId!!, o.id.value, why))
                "Started “${p.name}” on ${o.number}"
            }
            AutomationActionKind.WEBHOOK -> {
                val body =
                    automations.json.writeValueAsString(
                        mapOf(
                            "automation" to mapOf("id" to automation.id.toString(), "name" to automation.name),
                            "event" to
                                mapOf(
                                    "id" to envelope.id.toString(),
                                    "type" to envelope.eventType,
                                    "actor" to envelope.actor,
                                    "occurredAt" to envelope.occurredAt.toString(),
                                    "payload" to envelope.payload,
                                ),
                            "object" to view(obj),
                        ),
                    )
                val timestamp = clock.instant().epochSecond.toString()
                val request =
                    HttpRequest
                        .newBuilder(URI(a.url!!))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-PLM-Timestamp", timestamp)
                        .apply {
                            a.secret?.takeIf { it.isNotBlank() }?.let { s ->
                                val mac =
                                    Mac
                                        .getInstance(
                                            "HmacSHA256",
                                        ).apply { init(SecretKeySpec(s.toByteArray(), "HmacSHA256")) }
                                header(
                                    "X-PLM-Signature",
                                    "sha256=" + HexFormat.of().formatHex(mac.doFinal("$timestamp.$body".toByteArray())),
                                )
                            }
                        }.POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                val response =
                    try {
                        http.send(request, HttpResponse.BodyHandlers.ofString())
                    } catch (e: java.io.IOException) {
                        throw StepFailed(
                            "The call to ${URI(a.url).host} failed: ${e.message ?: e.javaClass.simpleName}",
                        )
                    }
                if (response.statusCode() !in
                    200..299
                ) {
                    throw StepFailed("${URI(a.url).host} answered ${response.statusCode()}")
                }
                "Called ${URI(a.url).host}: ${response.statusCode()}"
            }
            AutomationActionKind.RUN_SCRIPT -> {
                val r =
                    scripts.runAutomation(
                        system,
                        a.scriptId!!,
                        mapOf("event" to envelope.payload + ("type" to envelope.eventType), "object" to view(obj)),
                        "automation: ${automation.name}",
                    )
                if (!r.ok) throw StepFailed("The script failed: ${r.error}")
                "The script returned ${r.result?.let { automations.json.writeValueAsString(it) } ?: "nothing"}"
            }
        }
    }
}
