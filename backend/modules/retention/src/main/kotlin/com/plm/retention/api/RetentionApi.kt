package com.plm.retention.api

import com.plm.jobs.api.Job
import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

data class RetentionRule(
    val id: UUID,
    val name: String,
    /** The type (and its subtypes). */
    val typeId: UUID,
    /** Null: any state. */
    val state: String?,
    /** Days since the latest check-in. */
    val ageDays: Int,
    /** PURGE_ITERATIONS (keep each version's last iteration) or ARCHIVE (out of the system, restorable). */
    val action: String,
    val active: Boolean,
    val createdAt: Instant,
)

data class SaveRetentionRule(
    val name: String,
    val typeId: UUID,
    val state: String? = null,
    val ageDays: Int,
    val action: String,
    val active: Boolean = true,
)

data class LegalHold(
    val id: UUID,
    val name: String,
    val reason: String?,
    val objectIds: List<UUID>,
    val contextIds: List<UUID>,
    val createdAt: Instant,
    val createdBy: String,
    val releasedAt: Instant?,
    val releasedBy: String?,
)

data class SaveLegalHold(
    val name: String,
    val reason: String? = null,
    val objectIds: List<UUID> = emptyList(),
    val contextIds: List<UUID> = emptyList(),
)

/** What a retention run would do (or did) to one object. */
data class RetentionAction(
    val objectId: UUID,
    val number: String,
    val name: String,
    val version: String,
    val rule: String,
    /** PURGE_ITERATIONS, ARCHIVE, or SKIP. */
    val action: String,
    /** For a purge, the versions that go; for a skip, why. */
    val detail: String,
)

data class Archive(
    val id: UUID,
    val objectId: UUID,
    val number: String,
    val name: String,
    val contextId: UUID,
    val iterations: Int,
    val files: Int,
    val size: Long,
    val archivedAt: Instant,
    val archivedBy: String,
    val restoredAt: Instant?,
)

/**
 * Retention, legal hold, archive and purge (roadmap 9.8). Rules per type and state say, after so
 * many days since an object's latest check-in, to purge its old iterations (each version keeps its
 * last one) or to archive it: a self-contained package (every iteration, its files, structure and
 * document links) kept in the vault, the object removed, restorable as it was. A legal hold
 * freezes objects or whole contexts (and everything below): they can't be deleted, purged or
 * archived until it is released. [preview] is the dry run; [run] does it as a job with a report.
 * Site administrators only.
 *
 * Errors: `ADMIN_REQUIRED`, `INVALID_RETENTION_RULE`, `RETENTION_RULE_NOT_FOUND`,
 * `INVALID_LEGAL_HOLD`, `LEGAL_HOLD_NOT_FOUND`, `ARCHIVE_NOT_FOUND`, `ALREADY_RESTORED`,
 * `ON_LEGAL_HOLD` (from delete and purge).
 */
interface RetentionService {
    fun rules(actor: Actor): List<RetentionRule>

    fun saveRule(
        actor: Actor,
        id: UUID?,
        command: SaveRetentionRule,
    ): RetentionRule

    fun deleteRule(
        actor: Actor,
        id: UUID,
    )

    fun holds(actor: Actor): List<LegalHold>

    fun createHold(
        actor: Actor,
        command: SaveLegalHold,
    ): LegalHold

    fun releaseHold(
        actor: Actor,
        id: UUID,
    ): LegalHold

    /** The active holds covering an object (it, or a context it is in). */
    fun holdsOn(
        actor: Actor,
        objectId: UUID,
    ): List<LegalHold>

    fun preview(actor: Actor): List<RetentionAction>

    fun run(actor: Actor): Job

    fun archives(actor: Actor): List<Archive>

    /** Archives one object now, whatever the rules say (not if held or still used). */
    fun archive(
        actor: Actor,
        objectId: UUID,
    ): Archive

    fun restore(
        actor: Actor,
        archiveId: UUID,
    ): UUID
}
