package com.plm.events

import com.plm.events.infra.OutboxRelay
import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.redpanda.RedpandaContainer
import plm.testing.PlmIntegrationTest
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

private data class ThingHappened(
    val thingId: UUID,
    val step: Int,
) : DomainEvent {
    override val eventType = "test.ThingHappened.v1"
    override val aggregateType = "thing"
    override val aggregateId = thingId
}

@PlmIntegrationTest
@Import(OutboxRelayIT.Kafka::class)
// Drive the relay by hand: no background polling during the test.
@TestPropertySource(properties = ["plm.events.relay.poll-interval=1h", "plm.events.topic=test.events"])
internal class OutboxRelayIT(
    @Autowired private val events: DomainEvents,
    @Autowired private val relay: OutboxRelay,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val redpanda: RedpandaContainer,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class Kafka {
        @Bean
        @ServiceConnection
        fun redpanda() = RedpandaContainer("redpandadata/redpanda:v26.2.7")
    }

    private val actor = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val json = JsonMapper.builder().build()

    private fun consumeAll(expected: Int): List<ConsumerRecord<String, String>> {
        val props =
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "test-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            )
        KafkaConsumer(props, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.subscribe(listOf("test.events"))
            val records = mutableListOf<ConsumerRecord<String, String>>()
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            while (records.size < expected && System.nanoTime() < deadline) {
                records += consumer.poll(Duration.ofMillis(500))
            }
            return records
        }
    }

    @Test
    fun `relays committed events to Kafka in order, keyed by aggregate`() {
        val thing = UUID.randomUUID()
        tx.executeWithoutResult { (1..3).forEach { events.publish(actor, ThingHappened(thing, it)) } }

        do {
            val relayed = relay.relayBatch()
        } while (relayed > 0)

        val records = consumeAll(3).filter { it.key() == thing.toString() }
        assertThat(records).hasSize(3)
        assertThat(
            records.map {
                json
                    .readTree(it.value())
                    .path("payload")
                    .path("step")
                    .asInt()
            },
        ).containsExactly(1, 2, 3)

        val first = json.readTree(records.first().value())
        assertThat(first.path("eventType").asString()).isEqualTo("test.ThingHappened.v1")
        assertThat(first.path("actor").asString()).isEqualTo("alice")
        assertThat(
            first.path("payload").has("eventType"),
        ).describedAs("envelope fields stay out of the payload").isFalse()
        assertThat(
            String(
                records
                    .first()
                    .headers()
                    .lastHeader("plm-event-type")
                    .value(),
            ),
        ).isEqualTo("test.ThingHappened.v1")

        val pending =
            jdbc
                .sql("select count(*) from outbox_event where aggregate_id = :id and published_at is null")
                .param("id", thing)
                .query(Long::class.java)
                .single()
        assertThat(pending).isZero()
    }

    @Test
    fun `events of a rolled-back transaction are never published`() {
        val thing = UUID.randomUUID()

        assertThatThrownBy {
            tx.executeWithoutResult {
                events.publish(actor, ThingHappened(thing, 1))
                error("boom")
            }
        }.hasMessage("boom")

        val stored =
            jdbc
                .sql(
                    "select count(*) from outbox_event where aggregate_id = :id",
                ).param("id", thing)
                .query(Long::class.java)
                .single()
        assertThat(stored).isZero()
    }

    @Test
    fun `publishing outside a transaction is a programming error`() {
        assertThatThrownBy { events.publish(actor, ThingHappened(UUID.randomUUID(), 1)) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
