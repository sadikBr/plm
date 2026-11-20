package com.plm.audit.domain

import com.plm.audit.api.AuditEntry
import com.plm.audit.api.AuditService
import com.plm.audit.infra.AuditRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.events.EventEnvelope
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val MAX_LIMIT = 500

@Service
internal class DefaultAuditService(
    private val audit: AuditRepository,
) : AuditService {
    /**
     * Runs synchronously inside the transaction that published the event. If the audit row cannot
     * be written, the whole change rolls back: no change without an audit record.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(event: EventEnvelope) {
        audit.insert(event)
    }

    @Transactional(readOnly = true)
    override fun forAggregate(
        actor: Actor,
        aggregateId: UUID,
        limit: Int,
        before: Long?,
    ): List<AuditEntry> {
        requireAdmin(actor)
        return audit.find(actor.tenant, aggregateId, limit.coerceIn(1, MAX_LIMIT), before)
    }

    @Transactional(readOnly = true)
    override fun recent(
        actor: Actor,
        limit: Int,
        before: Long?,
    ): List<AuditEntry> {
        requireAdmin(actor)
        return audit.find(actor.tenant, null, limit.coerceIn(1, MAX_LIMIT), before)
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators can read the audit log",
            )
        }
    }
}
