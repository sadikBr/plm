package com.plm.extensions.domain

import com.plm.extensions.api.EventSubscription
import com.plm.extensions.api.EventSubscriptionChanged
import com.plm.extensions.api.EventSubscriptionService
import com.plm.extensions.api.EventSubscriptionSettings
import com.plm.extensions.api.SubscriptionHealth
import com.plm.extensions.api.WebhookDelivery
import com.plm.extensions.infra.DueDelivery
import com.plm.extensions.infra.ExtensionRepository
import com.plm.extensions.infra.WebhookRepository
import com.plm.platform.actor.Actor
import com.plm.platform.contract.PlatformContract
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.events.EventEnvelope
import com.plm.platform.expression.ExpressionException
import com.plm.platform.expression.ExpressionKind
import com.plm.platform.expression.Expressions
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID

private val log = LoggerFactory.getLogger(EventWebhooks::class.java)
private const val BATCH = 20
private const val MAX_ATTEMPTS = 20
private val LEASE: Duration = Duration.ofSeconds(60)
private val MAX_BACKOFF: Duration = Duration.ofHours(1)

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class WebhookScheduling

/**
 * Event subscriptions and their webhooks (roadmap 2.6).
 *
 * - **Queueing**: an in-process listener runs in the transaction that published the event, and
 *   queues one delivery per matching subscription. The event and its deliveries commit or roll back
 *   together, so nothing is lost or sent for a change that didn't happen. Queueing never fails the
 *   change: problems are logged (and a filter that can't be evaluated queues a dead delivery, so the
 *   administrator sees it).
 * - **Sending**: a scheduled sender claims due deliveries (leased with `SKIP LOCKED`, so several
 *   instances can run), POSTs them signed, without a transaction held, and records the outcome.
 *   Failures are retried after `backoff × 2^attempt` (at most an hour) until the subscription's
 *   attempts are used up; the delivery is then DEAD and can be redelivered from the admin screen.
 *   At least once: receivers deduplicate on `X-PLM-Delivery`.
 */
@Service
internal class EventWebhooks(
    private val webhooks: WebhookRepository,
    private val extensions: ExtensionRepository,
    private val events: DomainEvents,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @Value("\${plm.webhooks.sender.enabled:true}") private val senderEnabled: Boolean,
    @Value("\${plm.webhooks.backoff:10s}") private val backoff: Duration,
    @Value("\${plm.webhooks.timeout:10s}") private val timeout: Duration,
) : EventSubscriptionService {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    // ---------- Subscriptions ----------

    override fun list(actor: Actor): List<EventSubscription> {
        requireAdmin(actor)
        return all(actor.tenant)
    }

    override fun health(actor: Actor): List<SubscriptionHealth> {
        requireAdmin(actor)
        return webhooks.health(actor.tenant)
    }

    override fun define(
        actor: Actor,
        settings: EventSubscriptionSettings,
    ): EventSubscription {
        requireAdmin(actor)
        val secret =
            settings.secret?.takeIf { it.isNotBlank() }
                ?: throw invalid("A new subscription needs a secret to sign with")
        return save(actor, UuidV7.generate(clock), settings, secret)
    }

    override fun update(
        actor: Actor,
        id: UUID,
        settings: EventSubscriptionSettings,
    ): EventSubscription {
        requireAdmin(actor)
        val current = webhooks.secretOf(actor.tenant, id) ?: throw subscriptionNotFound(id)
        return save(actor, id, settings, settings.secret?.takeIf { it.isNotBlank() } ?: current)
    }

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        tx.executeWithoutResult {
            val s = webhooks.subscription(actor.tenant, id) ?: throw subscriptionNotFound(id)
            webhooks.delete(actor.tenant, id)
            events.publish(actor, EventSubscriptionChanged(id, s.name, deleted = true))
        }
    }

    override fun deliveries(
        actor: Actor,
        subscriptionId: String?,
        status: String?,
        limit: Int,
    ): List<WebhookDelivery> {
        requireAdmin(actor)
        return webhooks.deliveries(actor.tenant, subscriptionId, status, limit.coerceIn(1, 500))
    }

    override fun redeliver(
        actor: Actor,
        id: UUID,
    ): WebhookDelivery {
        requireAdmin(actor)
        if (!webhooks.redeliver(actor.tenant, id, clock.instant())) {
            throw NotFoundException("DELIVERY_NOT_FOUND", "Delivery $id does not exist", mapOf("deliveryId" to id))
        }
        return webhooks.delivery(actor.tenant, id)!!
    }

    private fun save(
        actor: Actor,
        id: UUID,
        s: EventSubscriptionSettings,
        secret: String,
    ): EventSubscription {
        val name = s.name.trim()
        if (name.isEmpty() || name.length > 80) throw invalid("A subscription needs a name of at most 80 characters")
        if (s.events.isEmpty()) throw invalid("Choose the events it's for")
        s.events.filterNot { EVENT_PATTERN.matches(it) }.takeIf { it.isNotEmpty() }?.let {
            throw invalid(
                "Not event types: ${it.joinToString()} (e.g. versioning.ObjectStateChanged.v1, versioning.* or *)",
            )
        }
        val url = s.url.trim()
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null ||
            uri.scheme !in setOf("https", "http") ||
            uri.host == null
        ) {
            throw invalid("The address must be a web address (https://...)")
        }
        if (s.maxAttempts !in 1..MAX_ATTEMPTS) throw invalid("Attempts must be 1 to $MAX_ATTEMPTS")
        val filter = s.filter?.trim()?.ifEmpty { null }
        filter?.let { f ->
            Expressions.check(f, ExpressionKind.CONDITION).firstOrNull()?.let {
                throw ValidationException("INVALID_EXPRESSION", "The filter isn't valid: ${it.message}")
            }
        }
        val subscription =
            EventSubscription(
                id = id.toString(),
                name = name,
                events = s.events.distinct(),
                filter = filter,
                url = url,
                maxAttempts = s.maxAttempts,
                active = s.active,
                source = "site",
                updatedAt = clock.instant(),
                updatedBy = actor.principal.value,
            )
        tx.executeWithoutResult {
            webhooks.save(actor.tenant, subscription, secret)
            events.publish(actor, EventSubscriptionChanged(id, name))
        }
        return subscription
    }

    /** The site's subscriptions, then each active extension's. */
    private fun all(tenant: TenantId): List<EventSubscription> =
        webhooks.subscriptions(tenant) +
            activeExtensions(tenant).flatMap { row ->
                val m = Manifests.read(row.source) ?: row.manifest
                m.subscriptions.map { sub ->
                    EventSubscription(
                        id = "${m.id}.${sub.id}",
                        name = sub.name ?: sub.id,
                        events = sub.events,
                        filter = sub.filter,
                        url =
                            EXTENSION_CONFIG.replace(
                                sub.deliver.webhook.url,
                            ) { row.config[it.groupValues[1]]?.toString().orEmpty() },
                        maxAttempts = sub.deliver.webhook.retries,
                        active = true,
                        source = m.id,
                        updatedAt = row.updatedAt,
                        updatedBy = row.updatedBy,
                    )
                }
            }

    private fun activeExtensions(tenant: TenantId) =
        extensions.all(tenant).filter {
            it.enabled &&
                Versions.Range.parse(it.manifest.platform).includes(PlatformContract.VERSION)
        }

    // ---------- Queueing (in the event's transaction) ----------

    @EventListener(EventEnvelope::class)
    fun queue(event: EventEnvelope) {
        // Our own bookkeeping isn't of interest to subscribers, and must never loop.
        if (event.eventType == "extensions.EventSubscriptionChanged.v1") return
        try {
            val tenant = TenantId(event.tenantId)
            val subscriptions = all(tenant).filter { it.active && it.events.any { p -> matches(p, event.eventType) } }
            if (subscriptions.isEmpty()) return
            val variables = mapOf("event" to eventVariables(event))
            for (s in subscriptions) {
                val deliveryId = UuidV7.generate(clock)
                val filter = s.filter
                val error: String? =
                    if (filter == null) {
                        null
                    } else {
                        try {
                            if (!Expressions.test(filter, variables)) continue
                            null
                        } catch (e: ExpressionException) {
                            "The filter couldn't be evaluated: ${e.message}"
                        }
                    }
                val body =
                    Manifests.json.writeValueAsString(
                        mapOf(
                            "deliveryId" to deliveryId.toString(),
                            "subscription" to s.id,
                            "event" to
                                mapOf(
                                    "id" to event.id.toString(),
                                    "type" to event.eventType,
                                    "aggregateType" to event.aggregateType,
                                    "aggregateId" to event.aggregateId.toString(),
                                    "occurredAt" to event.occurredAt.toString(),
                                    "actor" to event.actor,
                                    "payload" to event.payload,
                                ),
                        ),
                    )
                webhooks.enqueue(
                    tenant,
                    deliveryId,
                    s,
                    event.id,
                    event.eventType,
                    event.aggregateId,
                    s.url,
                    body,
                    clock.instant(),
                    error,
                )
            }
        } catch (e: Exception) {
            log.error("Couldn't queue webhooks for event {} ({})", event.id, event.eventType, e)
        }
    }

    private fun eventVariables(e: EventEnvelope): Map<String, Any?> =
        e.payload +
            mapOf(
                "type" to e.eventType,
                "aggregateType" to e.aggregateType,
                "aggregateId" to e.aggregateId.toString(),
                "occurredAt" to e.occurredAt.toString(),
                "actor" to e.actor,
            )

    private fun matches(
        pattern: String,
        type: String,
    ) = pattern == "*" || pattern == type || (pattern.endsWith(".*") && type.startsWith(pattern.dropLast(1)))

    // ---------- Sending ----------

    @Scheduled(
        fixedDelayString = "\${plm.webhooks.poll-interval:1s}",
        initialDelayString = "\${plm.webhooks.initial-delay:5s}",
    )
    fun poll() {
        if (senderEnabled) deliverDue()
    }

    /** Sends what's due now. @return how many were attempted. */
    fun deliverDue(): Int {
        var total = 0
        do {
            val now = clock.instant()
            val batch = tx.execute { webhooks.claim(now, now.plus(LEASE), BATCH) }.orEmpty()
            batch.forEach(::send)
            total += batch.size
        } while (batch.size == BATCH)
        return total
    }

    private fun send(d: DueDelivery) {
        val secret = secretFor(d)
        val timestamp = clock.instant().epochSecond.toString()
        val started = System.nanoTime()
        var status: Int? = null
        val error: String? =
            if (secret == null) {
                "The subscription or its extension is gone"
            } else {
                try {
                    val request =
                        HttpRequest
                            .newBuilder(URI(d.url))
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .header("User-Agent", "PLM-Webhooks/1")
                            .header("X-PLM-Delivery", d.id.toString())
                            .header("X-PLM-Event", d.eventType)
                            .header("X-PLM-Timestamp", timestamp)
                            .header("X-PLM-Signature", Signatures.sign(secret, timestamp, d.body))
                            .POST(HttpRequest.BodyPublishers.ofString(d.body))
                            .build()
                    val response = http.send(request, HttpResponse.BodyHandlers.discarding())
                    status = response.statusCode()
                    if (status in 200..299) null else "The receiver answered $status"
                } catch (e: Exception) {
                    "${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
                }
            }
        val duration = (System.nanoTime() - started) / 1_000_000
        tx.executeWithoutResult {
            if (error == null) {
                webhooks.delivered(d.id, status!!, duration, clock.instant())
            } else {
                val attempt = d.attempts + 1
                val retryAt =
                    if (attempt >= d.maxAttempts || secret == null) {
                        null
                    } else {
                        clock.instant().plus(
                            minOf(backoff.multipliedBy(1L shl (attempt - 1).coerceAtMost(20)), MAX_BACKOFF),
                        )
                    }
                webhooks.failed(d.id, status, error, duration, retryAt)
            }
        }
    }

    /** The secret to sign with: the site subscription's, or the extension's setting. */
    private fun secretFor(d: DueDelivery): String? {
        val tenant = TenantId(d.tenantId)
        if (d.source ==
            "site"
        ) {
            return runCatching {
                UUID.fromString(
                    d.subscriptionId,
                )
            }.getOrNull()?.let { webhooks.secretOf(tenant, it) }
        }
        val row = extensions.find(tenant, d.source) ?: return null
        val m = Manifests.read(row.source) ?: row.manifest
        val sub = m.subscriptions.firstOrNull { "${m.id}.${it.id}" == d.subscriptionId } ?: return null
        return row.secrets[sub.deliver.webhook.secret]
    }

    private fun invalid(message: String) = ValidationException("INVALID_SUBSCRIPTION", message)

    private fun subscriptionNotFound(id: UUID) =
        NotFoundException("SUBSCRIPTION_NOT_FOUND", "Subscription $id does not exist", mapOf("subscriptionId" to id))

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators manage event subscriptions",
            )
        }
    }

    private companion object {
        val EXTENSION_CONFIG = Regex("\\$\\{config\\.([a-zA-Z0-9]+)}")
    }
}
