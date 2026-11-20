package com.plm.audit.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

data class AuditEntry(
    val seq: Long,
    val eventId: UUID,
    val occurredAt: Instant,
    val actor: String,
    /** The domain event type, e.g. `versioning.ObjectCheckedIn.v1`. */
    val action: String,
    val aggregateType: String,
    val aggregateId: UUID,
    val details: Map<String, Any?>,
)

/**
 * Reads the audit trail. Writing happens automatically: every domain event is recorded.
 * Reading is restricted to site administrators for now. Per-object audit access for users who
 * can read the object comes with the object views.
 */
interface AuditService {
    /** Newest first. [before] is a `seq` cursor for paging. */
    fun forAggregate(
        actor: Actor,
        aggregateId: UUID,
        limit: Int = 50,
        before: Long? = null,
    ): List<AuditEntry>

    fun recent(
        actor: Actor,
        limit: Int = 50,
        before: Long? = null,
    ): List<AuditEntry>
}
