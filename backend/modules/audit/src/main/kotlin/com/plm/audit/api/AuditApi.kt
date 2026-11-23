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

/** What a site audits, and for how long (roadmap 9.9). */
data class AuditConfig(
    /** Event kinds left out, as type prefixes, e.g. `jobs.` or `notifications.NotificationSent`. */
    val excluded: List<String>,
    /** Days entries are kept (at least 30); null: for ever. */
    val retentionDays: Int?,
    /** Kinds that are always audited: their prefixes can't be excluded. */
    val alwaysAudited: List<String>,
    val updatedAt: java.time.Instant?,
    val updatedBy: String?,
)

data class SaveAuditConfig(
    val excluded: List<String> = emptyList(),
    val retentionDays: Int? = null,
)

/** A kind of event the log holds: its module prefix, with how many entries. */
data class AuditEventKind(
    val kind: String,
    val entries: Long,
    val excluded: Boolean,
    val alwaysAudited: Boolean,
)

/**
 * Audit configuration (roadmap 9.9), site administrators only. Excluded kinds are no longer
 * written; security-relevant kinds (access, identity, audit itself, security labels) always are.
 * Past the retention period entries are purged daily (and on demand); each purge and each change
 * of the configuration is itself audited. Errors: `ADMIN_REQUIRED`, `INVALID_AUDIT_CONFIG`.
 */
interface AuditConfigService {
    fun config(actor: Actor): AuditConfig

    fun update(
        actor: Actor,
        command: SaveAuditConfig,
    ): AuditConfig

    fun kinds(actor: Actor): List<AuditEventKind>

    /** Purges entries past the retention period now; returns how many. */
    fun purge(actor: Actor): Long
}

data class AuditConfigChanged(
    val tenantId: UUID,
    val excluded: List<String>,
    val retentionDays: Int?,
) : com.plm.platform.events.DomainEvent {
    override val eventType = "audit.ConfigChanged.v1"
    override val aggregateType = "audit-config"
    override val aggregateId = tenantId
}

data class AuditPurged(
    val tenantId: UUID,
    val before: String,
    val entries: Long,
) : com.plm.platform.events.DomainEvent {
    override val eventType = "audit.Purged.v1"
    override val aggregateType = "audit-config"
    override val aggregateId = tenantId
}
