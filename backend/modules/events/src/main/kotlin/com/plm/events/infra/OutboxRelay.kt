package com.plm.events.infra

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@ConfigurationProperties("plm.events")
internal data class EventsProperties(
    /** Kafka topic all domain events are published to, keyed by aggregate id. */
    val topic: String = "plm.domain-events",
    val partitions: Int = 6,
    val replicas: Short = 1,
    val relay: Relay = Relay(),
) {
    data class Relay(
        val enabled: Boolean = true,
        val batchSize: Int = 200,
        val sendTimeout: Duration = Duration.ofSeconds(10),
        /** Published rows are deleted from the outbox after this long. Kafka is the durable log. */
        val retention: Duration = Duration.ofDays(7),
    )
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(EventsProperties::class)
@ConditionalOnProperty(prefix = "plm.events.relay", name = ["enabled"], havingValue = "true", matchIfMissing = true)
internal class EventsConfiguration {
    @Bean
    fun domainEventsTopic(props: EventsProperties): NewTopic = NewTopic(props.topic, props.partitions, props.replicas)
}

/**
 * Moves outbox rows to Kafka, in commit order, at least once.
 *
 * - A transaction-scoped advisory lock lets only one app instance relay at a time. That keeps
 *   ordering simple; a later step can shard by aggregate if throughput demands it.
 * - Rows are sent one by one in `seq` order. On the first failure the batch stops, so a stuck
 *   event is never overtaken by later events of the same aggregate.
 * - Consumers must be idempotent: deduplicate on the `plm-event-id` header.
 */
@Component
@ConditionalOnProperty(prefix = "plm.events.relay", name = ["enabled"], havingValue = "true", matchIfMissing = true)
internal class OutboxRelay(
    private val jdbc: JdbcClient,
    private val kafka: KafkaTemplate<String, String>,
    private val tx: TransactionTemplate,
    private val props: EventsProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    private data class Row(
        val seq: Long,
        val id: UUID,
        val tenantId: UUID,
        val eventType: String,
        val aggregateType: String,
        val aggregateId: UUID,
        val occurredAt: Timestamp,
        val actor: String,
        val payload: String,
    )

    @Scheduled(fixedDelayString = "\${plm.events.relay.poll-interval:500ms}")
    fun poll() {
        // Keep draining while full batches come back.
        do {
            val relayed = relayBatch()
        } while (relayed == props.relay.batchSize)
    }

    /** @return the number of rows published. */
    fun relayBatch(): Int =
        tx.execute {
            val locked =
                jdbc.sql("select pg_try_advisory_xact_lock($ADVISORY_LOCK_KEY)").query(Boolean::class.java).single()
            if (!locked) return@execute 0

            val rows =
                jdbc
                    .sql(
                        """
                        select seq, id, tenant_id, event_type, aggregate_type, aggregate_id, occurred_at, actor,
                               payload::text as payload
                        from outbox_event where published_at is null order by seq limit :limit
                        """,
                    ).param("limit", props.relay.batchSize)
                    .query { rs, _ ->
                        Row(
                            seq = rs.getLong("seq"),
                            id = rs.getObject("id", UUID::class.java),
                            tenantId = rs.getObject("tenant_id", UUID::class.java),
                            eventType = rs.getString("event_type"),
                            aggregateType = rs.getString("aggregate_type"),
                            aggregateId = rs.getObject("aggregate_id", UUID::class.java),
                            occurredAt = rs.getTimestamp("occurred_at"),
                            actor = rs.getString("actor"),
                            payload = rs.getString("payload"),
                        )
                    }.list()

            val published = mutableListOf<Long>()
            for (row in rows) {
                try {
                    send(row)
                    published += row.seq
                } catch (e: Exception) {
                    log.warn("Outbox relay failed for event {} ({}): {}", row.id, row.eventType, e.toString())
                    jdbc
                        .sql("update outbox_event set attempts = attempts + 1, last_error = :error where seq = :seq")
                        .param("error", e.toString().take(MAX_ERROR_LENGTH))
                        .param("seq", row.seq)
                        .update()
                    break
                }
            }
            if (published.isNotEmpty()) {
                jdbc
                    .sql("update outbox_event set published_at = :now where seq in (:seqs)")
                    .param("now", Timestamp.from(clock.instant()))
                    .param("seqs", published)
                    .update()
            }
            published.size
        }

    @Scheduled(fixedDelayString = "\${plm.events.relay.cleanup-interval:1h}", initialDelayString = "1m")
    fun deleteOldPublished() {
        val cutoff = clock.instant().minus(props.relay.retention)
        val deleted =
            jdbc
                .sql("delete from outbox_event where published_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update()
        if (deleted > 0) log.info("Deleted {} relayed outbox events older than {}", deleted, cutoff)
    }

    private fun send(row: Row) {
        val value =
            json.writeValueAsString(
                mapOf(
                    "id" to row.id,
                    "eventType" to row.eventType,
                    "tenantId" to row.tenantId,
                    "aggregateType" to row.aggregateType,
                    "aggregateId" to row.aggregateId,
                    "occurredAt" to row.occurredAt.toInstant().toString(),
                    "actor" to row.actor,
                    "payload" to json.readTree(row.payload),
                ),
            )
        val record = ProducerRecord(props.topic, row.aggregateId.toString(), value)
        record.headers().add("plm-event-id", row.id.toString().toByteArray(UTF_8))
        record.headers().add("plm-event-type", row.eventType.toByteArray(UTF_8))
        record.headers().add("plm-tenant-id", row.tenantId.toString().toByteArray(UTF_8))
        kafka.send(record).get(props.relay.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    private companion object {
        /** Arbitrary but fixed: identifies the outbox relay lock cluster-wide. */
        const val ADVISORY_LOCK_KEY = 7_000_001L
        const val MAX_ERROR_LENGTH = 2000
    }
}
