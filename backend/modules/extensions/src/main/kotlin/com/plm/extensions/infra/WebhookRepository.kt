package com.plm.extensions.infra

import com.plm.extensions.api.EventSubscription
import com.plm.extensions.api.SubscriptionHealth
import com.plm.extensions.api.WebhookDelivery
import com.plm.platform.error.ConflictException
import com.plm.platform.id.TenantId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A delivery claimed for sending: what to send, and how to sign it. */
internal data class DueDelivery(
    val id: UUID,
    val tenantId: UUID,
    val subscriptionId: String,
    val source: String,
    val eventType: String,
    val url: String,
    val body: String,
    val attempts: Int,
    val maxAttempts: Int,
)

@Repository
internal class WebhookRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    // ---------- Subscriptions ----------

    private val subscriptionMapper =
        RowMapper { rs, _ ->
            EventSubscription(
                id = rs.getObject("id", UUID::class.java).toString(),
                name = rs.getString("name"),
                events = json.readValue(rs.getString("events"), Array<String>::class.java).toList(),
                filter = rs.getString("filter"),
                url = rs.getString("url"),
                maxAttempts = rs.getInt("max_attempts"),
                active = rs.getBoolean("active"),
                source = "site",
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                updatedBy = rs.getString("updated_by"),
            )
        }

    fun subscriptions(tenant: TenantId): List<EventSubscription> =
        jdbc
            .sql("select * from event_subscription where tenant_id = :tenant order by name")
            .param("tenant", tenant.value)
            .query(subscriptionMapper)
            .list()

    fun subscription(
        tenant: TenantId,
        id: UUID,
    ): EventSubscription? =
        jdbc
            .sql("select * from event_subscription where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query(subscriptionMapper)
            .optional()
            .orElse(null)

    fun secretOf(
        tenant: TenantId,
        id: UUID,
    ): String? =
        jdbc
            .sql("select secret from event_subscription where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun save(
        tenant: TenantId,
        s: EventSubscription,
        secret: String,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into event_subscription (id, tenant_id, name, events, filter, url, secret, max_attempts, active, updated_at, updated_by)
                    values (:id, :tenant, :name, cast(:events as jsonb), :filter, :url, :secret, :max, :active, :at, :by)
                    on conflict (id) do update set
                        name = excluded.name, events = excluded.events, filter = excluded.filter, url = excluded.url,
                        secret = excluded.secret, max_attempts = excluded.max_attempts, active = excluded.active,
                        updated_at = excluded.updated_at, updated_by = excluded.updated_by
                    """.trimIndent(),
                ).param("id", UUID.fromString(s.id))
                .param("tenant", tenant.value)
                .param("name", s.name)
                .param("events", json.writeValueAsString(s.events))
                .param("filter", s.filter)
                .param("url", s.url)
                .param("secret", secret)
                .param("max", s.maxAttempts)
                .param("active", s.active)
                .param("at", Timestamp.from(s.updatedAt))
                .param("by", s.updatedBy)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("DUPLICATE_SUBSCRIPTION_NAME", "There's already a subscription named '${s.name}'")
        }
    }

    fun delete(
        tenant: TenantId,
        id: UUID,
    ) {
        jdbc
            .sql("delete from event_subscription where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .update()
    }

    // ---------- Deliveries ----------

    fun enqueue(
        tenant: TenantId,
        id: UUID,
        subscription: EventSubscription,
        eventId: UUID,
        eventType: String,
        aggregateId: UUID,
        url: String,
        body: String,
        now: Instant,
        error: String? = null,
    ) {
        jdbc
            .sql(
                """
                insert into webhook_delivery (id, tenant_id, subscription_id, subscription_name, source, event_id, event_type,
                    aggregate_id, url, body, status, max_attempts, next_attempt_at, last_error, created_at)
                values (:id, :tenant, :sub, :name, :source, :event, :type, :aggregate, :url, cast(:body as jsonb),
                    :status, :max, :next, :error, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", tenant.value)
            .param("sub", subscription.id)
            .param("name", subscription.name)
            .param("source", subscription.source)
            .param("event", eventId)
            .param("type", eventType)
            .param("aggregate", aggregateId)
            .param("url", url)
            .param("body", body)
            .param("status", if (error == null) "PENDING" else "DEAD")
            .param("max", subscription.maxAttempts)
            .param("next", if (error == null) Timestamp.from(now) else null)
            .param("error", error)
            .param("now", Timestamp.from(now))
            .update()
    }

    /**
     * Claims up to [limit] due deliveries for [lease]: they won't be due again until it runs out,
     * so a crash mid-send just means a retry. Other instances skip rows being claimed.
     */
    fun claim(
        now: Instant,
        lease: Instant,
        limit: Int,
    ): List<DueDelivery> =
        jdbc
            .sql(
                """
                update webhook_delivery set next_attempt_at = :lease
                where id in (
                    select id from webhook_delivery
                    where status = 'PENDING' and next_attempt_at <= :now
                    order by next_attempt_at, seq limit :limit
                    for update skip locked)
                returning id, tenant_id, subscription_id, source, event_type, url, body::text as body, attempts, max_attempts
                """.trimIndent(),
            ).param("now", Timestamp.from(now))
            .param("lease", Timestamp.from(lease))
            .param("limit", limit)
            .query { rs, _ ->
                DueDelivery(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    subscriptionId = rs.getString("subscription_id"),
                    source = rs.getString("source"),
                    eventType = rs.getString("event_type"),
                    url = rs.getString("url"),
                    body = rs.getString("body"),
                    attempts = rs.getInt("attempts"),
                    maxAttempts = rs.getInt("max_attempts"),
                )
            }.list()

    fun delivered(
        id: UUID,
        httpStatus: Int,
        durationMs: Long,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                update webhook_delivery set status = 'DELIVERED', attempts = attempts + 1, last_http_status = :http,
                    last_error = null, duration_ms = :duration, delivered_at = :now, next_attempt_at = null
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .param("http", httpStatus)
            .param("duration", durationMs)
            .param("now", Timestamp.from(now))
            .update()
    }

    /** A failed attempt: retried at [retryAt], or dead when it was the last one ([retryAt] null). */
    fun failed(
        id: UUID,
        httpStatus: Int?,
        error: String,
        durationMs: Long,
        retryAt: Instant?,
    ) {
        jdbc
            .sql(
                """
                update webhook_delivery set attempts = attempts + 1, last_http_status = :http, last_error = :error,
                    duration_ms = :duration, next_attempt_at = :retry,
                    status = case when cast(:retry as timestamptz) is null then 'DEAD' else 'PENDING' end
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .param("http", httpStatus)
            .param("error", error.take(500))
            .param("duration", durationMs)
            .param("retry", retryAt?.let(Timestamp::from))
            .update()
    }

    fun redeliver(
        tenant: TenantId,
        id: UUID,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                update webhook_delivery set status = 'PENDING', attempts = 0, next_attempt_at = :now, delivered_at = null
                where tenant_id = :tenant and id = :id
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", id)
            .param("now", Timestamp.from(now))
            .update() > 0

    private val deliveryMapper =
        RowMapper { rs, _ ->
            WebhookDelivery(
                id = rs.getObject("id", UUID::class.java),
                subscriptionId = rs.getString("subscription_id"),
                subscriptionName = rs.getString("subscription_name"),
                source = rs.getString("source"),
                eventId = rs.getObject("event_id", UUID::class.java),
                eventType = rs.getString("event_type"),
                aggregateId = rs.getObject("aggregate_id", UUID::class.java),
                url = rs.getString("url"),
                status = rs.getString("status"),
                attempts = rs.getInt("attempts"),
                maxAttempts = rs.getInt("max_attempts"),
                nextAttemptAt = rs.getTimestamp("next_attempt_at")?.toInstant(),
                lastHttpStatus = rs.getObject("last_http_status") as Int?,
                lastError = rs.getString("last_error"),
                durationMs = rs.getObject("duration_ms") as Long?,
                createdAt = rs.getTimestamp("created_at").toInstant(),
                deliveredAt = rs.getTimestamp("delivered_at")?.toInstant(),
            )
        }

    fun delivery(
        tenant: TenantId,
        id: UUID,
    ): WebhookDelivery? =
        jdbc
            .sql("select * from webhook_delivery where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query(deliveryMapper)
            .optional()
            .orElse(null)

    fun deliveries(
        tenant: TenantId,
        subscriptionId: String?,
        status: String?,
        limit: Int,
    ): List<WebhookDelivery> =
        jdbc
            .sql(
                """
                select * from webhook_delivery where tenant_id = :tenant
                  and (cast(:sub as text) is null or subscription_id = :sub)
                  and (cast(:status as text) is null or status = :status)
                order by seq desc limit :limit
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("sub", subscriptionId)
            .param("status", status)
            .param("limit", limit)
            .query(deliveryMapper)
            .list()

    fun health(tenant: TenantId): List<SubscriptionHealth> =
        jdbc
            .sql(
                """
                select subscription_id,
                    count(*) filter (where status = 'DELIVERED') as delivered,
                    count(*) filter (where status = 'PENDING') as pending,
                    count(*) filter (where status = 'DEAD') as dead
                from webhook_delivery where tenant_id = :tenant group by subscription_id
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .query { rs, _ -> SubscriptionHealth(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)) }
            .list()
}
