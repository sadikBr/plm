package com.plm.audit.infra

import com.plm.audit.api.AuditEntry
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class AuditRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    fun insert(event: EventEnvelope) {
        jdbc
            .sql(
                """
                insert into audit_log (event_id, tenant_id, occurred_at, actor, action, aggregate_type, aggregate_id, details)
                values (:eventId, :tenant, :occurredAt, :actor, :action, :aggregateType, :aggregateId, cast(:details as jsonb))
                """,
            ).param("eventId", event.id)
            .param("tenant", event.tenantId)
            .param("occurredAt", Timestamp.from(event.occurredAt))
            .param("actor", event.actor)
            .param("action", event.eventType)
            .param("aggregateType", event.aggregateType)
            .param("aggregateId", event.aggregateId)
            .param("details", json.writeValueAsString(event.payload))
            .update()
    }

    fun find(
        tenant: TenantId,
        aggregateId: UUID?,
        limit: Int,
        before: Long?,
    ): List<AuditEntry> =
        jdbc
            .sql(
                """
                select * from audit_log
                where tenant_id = :tenant
                  and (cast(:aggregate as uuid) is null or aggregate_id = :aggregate)
                  and (cast(:before as bigint) is null or seq < :before)
                order by seq desc
                limit :limit
                """,
            ).param("tenant", tenant.value)
            .param("aggregate", aggregateId)
            .param("before", before)
            .param("limit", limit)
            .query { rs, _ ->
                AuditEntry(
                    seq = rs.getLong("seq"),
                    eventId = rs.getObject("event_id", UUID::class.java),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                    actor = rs.getString("actor"),
                    action = rs.getString("action"),
                    aggregateType = rs.getString("aggregate_type"),
                    aggregateId = rs.getObject("aggregate_id", UUID::class.java),
                    details = json.readValue(rs.getString("details"), mapType),
                )
            }.list()
}
