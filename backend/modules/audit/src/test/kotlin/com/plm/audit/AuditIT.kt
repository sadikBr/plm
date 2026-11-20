package com.plm.audit

import com.plm.audit.api.AuditService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.events.DomainEvent
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import plm.testing.PlmIntegrationTest
import java.util.UUID

private data class Renamed(
    val thingId: UUID,
    val from: String,
    val to: String,
) : DomainEvent {
    override val eventType = "test.Renamed.v1"
    override val aggregateType = "thing"
    override val aggregateId = thingId
}

@PlmIntegrationTest
class AuditIT(
    @Autowired private val events: DomainEvents,
    @Autowired private val audit: AuditService,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
) {
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("root"), siteAdmin = true)

    @Test
    fun `every domain event is recorded, newest first`() {
        val thing = UUID.randomUUID()
        tx.executeWithoutResult {
            events.publish(alice, Renamed(thing, "a", "b"))
            events.publish(alice, Renamed(thing, "b", "c"))
        }

        val entries = audit.forAggregate(admin, thing)

        assertThat(entries).hasSize(2)
        assertThat(entries.map { it.details["to"] }).containsExactly("c", "b")
        assertThat(entries.first().actor).isEqualTo("alice")
        assertThat(entries.first().action).isEqualTo("test.Renamed.v1")
        assertThat(audit.forAggregate(admin, thing, limit = 1, before = entries.first().seq).single().details["to"])
            .isEqualTo("b")
    }

    @Test
    fun `nothing is recorded when the change rolls back`() {
        val thing = UUID.randomUUID()
        runCatching {
            tx.executeWithoutResult {
                events.publish(alice, Renamed(thing, "a", "b"))
                error("rollback")
            }
        }

        assertThat(audit.forAggregate(admin, thing)).isEmpty()
    }

    @Test
    fun `the audit log is append-only`() {
        tx.executeWithoutResult { events.publish(alice, Renamed(UUID.randomUUID(), "a", "b")) }

        assertThatThrownBy { jdbc.sql("update audit_log set actor = 'mallory'").update() }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("append-only")
        assertThatThrownBy { jdbc.sql("delete from audit_log").update() }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { jdbc.sql("truncate audit_log").update() }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `only site administrators can read it`() {
        assertThatThrownBy { audit.recent(alice) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ADMIN_REQUIRED") }
    }
}
