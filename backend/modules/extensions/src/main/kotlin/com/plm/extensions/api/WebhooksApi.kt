package com.plm.extensions.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

/**
 * A subscription to events (roadmap 2.6): every event whose type matches [events] (exact, or a
 * wildcard such as `versioning.*` or `*`) and, if given, the CEL [filter] (over `event`: its
 * payload plus `type`, `aggregateType`, `aggregateId`, `occurredAt`, `actor`) is POSTed to [url],
 * signed, and retried up to [maxAttempts] times.
 */
data class EventSubscription(
    /** A UUID for the site's subscriptions, `<extension>.<subscription>` for an extension's. */
    val id: String,
    val name: String,
    val events: List<String>,
    val filter: String?,
    val url: String,
    val maxAttempts: Int,
    val active: Boolean,
    /** `site` or the extension that declared it. */
    val source: String,
    val updatedAt: Instant?,
    val updatedBy: String?,
)

data class EventSubscriptionSettings(
    val name: String,
    val events: List<String>,
    val filter: String? = null,
    val url: String,
    /** Signs the requests. Required for a new subscription; null keeps the current one. */
    val secret: String? = null,
    val maxAttempts: Int = 8,
    val active: Boolean = true,
)

/** A subscription in an extension's manifest (`subscriptions:`). */
data class ManifestSubscription(
    val id: String,
    val name: String? = null,
    val events: List<String>,
    val filter: String? = null,
    val deliver: SubscriptionDelivery,
)

data class SubscriptionDelivery(
    val webhook: EventWebhook,
)

data class EventWebhook(
    /** `${config.<key>}` is filled in. */
    val url: String,
    /** The `secret` setting the requests are signed with. */
    val secret: String,
    val retries: Int = 8,
)

data class WebhookDelivery(
    val id: UUID,
    val subscriptionId: String,
    val subscriptionName: String,
    val source: String,
    val eventId: UUID,
    val eventType: String,
    val aggregateId: UUID,
    val url: String,
    /** PENDING (waiting or retrying), DELIVERED, DEAD (gave up: redeliver it once fixed). */
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val nextAttemptAt: Instant?,
    val lastHttpStatus: Int?,
    val lastError: String?,
    val durationMs: Long?,
    val createdAt: Instant,
    val deliveredAt: Instant?,
)

/** How a subscription is doing, for the admin's overview. */
data class SubscriptionHealth(
    val subscriptionId: String,
    val delivered: Int,
    val pending: Int,
    val dead: Int,
)

/**
 * Event subscriptions and their deliveries. Site administrators only (`ADMIN_REQUIRED`). Error
 * codes: `SUBSCRIPTION_NOT_FOUND`, `INVALID_SUBSCRIPTION`, `INVALID_EXPRESSION`,
 * `DUPLICATE_SUBSCRIPTION_NAME`, `DELIVERY_NOT_FOUND`.
 */
interface EventSubscriptionService {
    /** The site's subscriptions, then active extensions'. */
    fun list(actor: Actor): List<EventSubscription>

    fun health(actor: Actor): List<SubscriptionHealth>

    fun define(
        actor: Actor,
        settings: EventSubscriptionSettings,
    ): EventSubscription

    fun update(
        actor: Actor,
        id: UUID,
        settings: EventSubscriptionSettings,
    ): EventSubscription

    fun delete(
        actor: Actor,
        id: UUID,
    )

    /** The latest deliveries, newest first, optionally of one subscription and/or status. */
    fun deliveries(
        actor: Actor,
        subscriptionId: String? = null,
        status: String? = null,
        limit: Int = 100,
    ): List<WebhookDelivery>

    /** Sends a delivery again, from scratch (attempts reset), e.g. once the receiver is fixed. */
    fun redeliver(
        actor: Actor,
        id: UUID,
    ): WebhookDelivery
}

data class EventSubscriptionChanged(
    val subscriptionId: UUID,
    val name: String,
    val deleted: Boolean = false,
) : DomainEvent {
    override val eventType = "extensions.EventSubscriptionChanged.v1"
    override val aggregateType = "event-subscription"
    override val aggregateId: UUID = subscriptionId
}
