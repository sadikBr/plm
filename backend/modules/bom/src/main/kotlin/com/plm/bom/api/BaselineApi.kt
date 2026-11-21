package com.plm.bom.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.versioning.api.MasterId
import java.time.Instant
import java.util.UUID

enum class BaselineStatus { OPEN, FROZEN }

data class Baseline(
    val id: UUID,
    val number: String,
    val name: String,
    val description: String?,
    val contextId: UUID,
    val status: BaselineStatus,
    val members: Int,
    val createdAt: Instant,
    val createdBy: String,
    val frozenAt: Instant?,
    val frozenBy: String?,
)

/** An object as it stands in a baseline, or as collected (roadmap 4.10). */
data class CollectedObject(
    val item: UsedPart,
    /** TOP, STRUCTURE, DESCRIBED_BY, REFERENCES */
    val relation: String,
    /** Levels below the top (0 for the top, documents count as their part's level). */
    val level: Int,
)

data class NewBaseline(
    val name: String,
    val description: String? = null,
    val contextId: UUID,
)

/** What to collect from a part: its structure under a specification, and its documents. */
data class CollectRequest(
    val partId: UUID,
    val spec: ConfigSpec = ConfigSpec.LATEST,
    val depth: Int = 50,
    val documents: Boolean = true,
)

/**
 * Baselines (roadmap 4.10): named sets of exact iterations, open while being built and then frozen
 * for good. Anyone who can read the context sees the baseline; members they may not read stay
 * hidden. Collecting gathers a part, its structure (under a specification) and its documents, for
 * a baseline or for bulk work. Errors: `BASELINE_NOT_FOUND`, `BASELINE_FROZEN`, `INVALID_BASELINE`.
 */
interface BaselineService {
    fun list(
        actor: Actor,
        containing: MasterId? = null,
    ): List<Baseline>

    fun get(
        actor: Actor,
        id: UUID,
    ): Baseline

    fun members(
        actor: Actor,
        id: UUID,
    ): List<CollectedObject>

    fun create(
        actor: Actor,
        baseline: NewBaseline,
    ): Baseline

    /** Adds (or moves to the given iteration) these objects. */
    fun add(
        actor: Actor,
        id: UUID,
        iterationIds: List<UUID>,
    ): Baseline

    fun remove(
        actor: Actor,
        id: UUID,
        masterIds: List<UUID>,
    ): Baseline

    fun freeze(
        actor: Actor,
        id: UUID,
    ): Baseline

    /** Open baselines only. */
    fun delete(
        actor: Actor,
        id: UUID,
    )

    fun collect(
        actor: Actor,
        request: CollectRequest,
    ): List<CollectedObject>
}

data class BaselineChanged(
    val baselineId: UUID,
    val number: String,
    /** CREATED, MEMBERS_ADDED, MEMBERS_REMOVED, FROZEN, DELETED */
    val change: String,
    val count: Int,
) : DomainEvent {
    override val eventType = "bom.BaselineChanged.v1"
    override val aggregateType = "baseline"
    override val aggregateId: UUID = baselineId
}
