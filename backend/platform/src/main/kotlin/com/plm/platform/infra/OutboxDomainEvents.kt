package com.plm.platform.infra

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.platform.events.DomainEvents
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.UuidV7
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock

@Component
internal class OutboxDomainEvents(
    private val jdbc: JdbcClient,
    private val listeners: ApplicationEventPublisher,
    private val clock: Clock,
) : DomainEvents {
    private val json = JsonMapper.builder().build()
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun publish(
        actor: Actor,
        event: DomainEvent,
    ): EventEnvelope {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "DomainEvents.publish must run inside the transaction that made the change"
        }
        val envelope =
            EventEnvelope(
                id = UuidV7.generate(clock),
                tenantId = actor.tenant.value,
                eventType = event.eventType,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                occurredAt = clock.instant(),
                actor = actor.principal.value,
                payload = json.convertValue(event, mapType),
            )
        jdbc
            .sql(
                """
                insert into outbox_event
                    (id, tenant_id, event_type, aggregate_type, aggregate_id, occurred_at, actor, payload)
                values (:id, :tenant, :type, :aggregateType, :aggregateId, :occurredAt, :actor, cast(:payload as jsonb))
                """,
            ).param("id", envelope.id)
            .param("tenant", envelope.tenantId)
            .param("type", envelope.eventType)
            .param("aggregateType", envelope.aggregateType)
            .param("aggregateId", envelope.aggregateId)
            .param("occurredAt", Timestamp.from(envelope.occurredAt))
            .param("actor", envelope.actor)
            .param("payload", json.writeValueAsString(envelope.payload))
            .update()
        listeners.publishEvent(envelope)
        return envelope
    }
}
