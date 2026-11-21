package com.plm.audit.infra

import com.plm.audit.api.AuditCount
import com.plm.audit.api.AuditEntry
import com.plm.audit.api.AuditQuery
import com.plm.audit.api.AuditReport
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

    private fun where(q: AuditQuery) =
        """
        where tenant_id = :tenant
          and (cast(:from as timestamptz) is null or occurred_at >= :from)
          and (cast(:to as timestamptz) is null or occurred_at < :to)
          and (cast(:who as text) is null or actor = :who)
          and (cast(:action as text) is null or action like :action || '%')
          and (cast(:aggregate as uuid) is null or aggregate_id = :aggregate)
          and (cast(:context as uuid) is null or aggregate_id = :context or details ->> 'contextId' in (
                select cast(descendant_id as text) from context_closure where ancestor_id = :context))
        """

    private fun JdbcClient.StatementSpec.bind(
        tenant: TenantId,
        q: AuditQuery,
    ) = param("tenant", tenant.value)
        .param("from", q.from?.let { java.sql.Timestamp.from(it) })
        .param("to", q.to?.let { java.sql.Timestamp.from(it) })
        .param("who", q.actor)
        .param("action", q.action)
        .param("aggregate", q.aggregateId)
        .param("context", q.contextId)

    fun report(
        tenant: TenantId,
        q: AuditQuery,
    ): AuditReport {
        val entries =
            jdbc
                .sql(
                    "select * from audit_log ${where(
                        q,
                    )} and (cast(:before as bigint) is null or seq < :before) order by seq desc limit :limit",
                ).bind(tenant, q)
                .param("before", q.before)
                .param("limit", q.limit)
                .query { rs, _ -> entry(rs) }
                .list()

        fun counts(column: String) =
            jdbc
                .sql(
                    "select $column as k, count(*) as n from audit_log ${where(
                        q,
                    )} group by $column order by n desc, k limit 12",
                ).bind(tenant, q)
                .query { rs, _ -> AuditCount(rs.getString("k"), rs.getLong("n")) }
                .list()
        val byAction = counts("action")
        val total =
            jdbc
                .sql(
                    "select count(*) from audit_log ${where(q)}",
                ).bind(tenant, q)
                .query(Long::class.java)
                .single()
        return AuditReport(entries, total, byAction, counts("actor"))
    }

    private fun entry(rs: java.sql.ResultSet) =
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
