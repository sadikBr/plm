package com.plm.platform.events

import com.fasterxml.jackson.annotation.JsonIgnore
import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/**
 * Something that happened in the domain. Implementations are immutable data classes whose
 * properties form the event payload. Use plain JSON-friendly types (UUID, String, numbers, maps).
 *
 * [eventType] is part of the public contract (consumers, audit log). Never change the meaning
 * of an existing type: publish a new `.v2` instead.
 */
interface DomainEvent {
    /** e.g. `versioning.ObjectCheckedIn.v1` */
    @get:JsonIgnore
    val eventType: String

    /** e.g. `versioned-object`. Kafka partitions by aggregate, so events of one aggregate stay ordered. */
    @get:JsonIgnore
    val aggregateType: String

    @get:JsonIgnore
    val aggregateId: UUID
}

/** A [DomainEvent] as stored in the outbox and delivered to listeners and Kafka. */
data class EventEnvelope(
    val id: UUID,
    val tenantId: UUID,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val actor: String,
    val payload: Map<String, Any?>,
)

/**
 * Publishes domain events through the transactional outbox (ADR 0008).
 *
 * [publish] must be called inside the transaction that made the change. The event is written to
 * the outbox in that same transaction, so the change and its event commit or roll back together.
 * In-process listeners (`@EventListener(EventEnvelope::class)`) run synchronously in the same
 * transaction. Use them only for work that must be atomic with the change, such as the audit log.
 * Everything else consumes from Kafka.
 */
interface DomainEvents {
    fun publish(
        actor: Actor,
        event: DomainEvent,
    ): EventEnvelope
}
