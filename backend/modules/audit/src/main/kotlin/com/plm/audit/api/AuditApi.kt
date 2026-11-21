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

/** Filters for an audit report (roadmap 3.10); all optional. */
data class AuditQuery(
    val from: Instant? = null,
    val to: Instant? = null,
    /** Who did it (a principal id). */
    val actor: String? = null,
    /** An event type or its start, e.g. `versioning.` or `versioning.ObjectStateChanged`. */
    val action: String? = null,
    /** Changes to objects and folders in this context and the ones under it. */
    val contextId: UUID? = null,
    val aggregateId: UUID? = null,
    val limit: Int = 200,
    val before: Long? = null,
)

data class AuditCount(
    val key: String,
    val count: Long,
)

data class AuditReport(
    val entries: List<AuditEntry>,
    /** Over everything matching, not just this page. */
    val total: Long,
    val byAction: List<AuditCount>,
    val byActor: List<AuditCount>,
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

    /**
     * An audit report (roadmap 3.10). Site administrators may report on everything; a context's
     * managers on that context (with [AuditQuery.contextId]). `ADMIN_REQUIRED`, `ACCESS_DENIED`.
     */
    fun report(
        actor: Actor,
        query: AuditQuery,
    ): AuditReport
}
