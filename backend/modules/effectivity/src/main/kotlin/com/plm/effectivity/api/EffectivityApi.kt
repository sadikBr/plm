package com.plm.effectivity.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

enum class EffectivityKind { DATE, SERIAL, LOT, BLOCK }

enum class EffectivityTarget { VERSION, LINE }

enum class EffectivityStatus { ACTUAL, PENDING }

/** A part, as the reader sees it. */
data class EffectivityItem(
    val id: UUID,
    val number: String?,
    val name: String?,
)

/** An end item serials, lots and blocks count in (roadmap 7.1). */
data class EffectivityContext(
    val id: UUID,
    val endItem: EffectivityItem,
    val description: String?,
    val createdAt: Instant,
    val createdBy: String,
)

/**
 * When a part's revision, or a line of its structure, applies (roadmap 7.1): from [from] to [to]
 * (inclusive; none: open-ended), as dates (yyyy-mm-dd), serial or block numbers, or lots, in end
 * item [context] (dates may have none).
 */
data class Effectivity(
    val id: UUID,
    val target: EffectivityTarget,
    val partId: UUID,
    val revision: String?,
    val lineId: UUID?,
    /** For lines: the line's number and the part it uses. */
    val lineNumber: Int?,
    val lineChild: EffectivityItem?,
    val context: EffectivityContext?,
    val kind: EffectivityKind,
    val from: String,
    val to: String?,
    val status: EffectivityStatus,
    val changeId: UUID?,
    val createdAt: Instant,
    val createdBy: String,
)

data class SetEffectivity(
    val kind: EffectivityKind,
    val from: String,
    val to: String? = null,
    /** The end item; required for serials, lots and blocks. */
    val endItemId: UUID? = null,
)

data class AddEffectivity(
    val target: EffectivityTarget,
    /** VERSION: the revision (e.g. B). */
    val revision: String? = null,
    /** LINE: a line of the part's working copy. */
    val lineId: UUID? = null,
    val kind: EffectivityKind,
    val from: String,
    val to: String? = null,
    val endItemId: UUID? = null,
    /** Set through this change notice: pending until it's resolved (roadmap 7.3). */
    val changeId: UUID? = null,
)

/**
 * Effectivity (roadmap 7.1). Reading needs read access to the part; setting a version's
 * effectivity needs MODIFY on it, a line's needs the part checked out to you (lines are edited on
 * the working copy and carried forward). Ranges of the same kind and end item may not overlap
 * between revisions of a part, or on one line. Errors: `INVALID_EFFECTIVITY`,
 * `OVERLAPPING_EFFECTIVITY`, `EFFECTIVITY_NOT_FOUND`, `CONTEXT_NOT_FOUND`, `NOT_CHECKED_OUT`.
 */
interface EffectivityService {
    fun contexts(actor: Actor): List<EffectivityContext>

    /** Makes [endItemId] (a part) an end item effectivity counts in; needs MODIFY on it. */
    fun createContext(
        actor: Actor,
        endItemId: UUID,
        description: String?,
    ): EffectivityContext

    fun deleteContext(
        actor: Actor,
        contextId: UUID,
    )

    /** The part's version effectivity, and its lines' (latest iteration, or your working copy). */
    fun forPart(
        actor: Actor,
        partId: UUID,
        workingCopy: Boolean = false,
    ): List<Effectivity>

    fun add(
        actor: Actor,
        partId: UUID,
        request: AddEffectivity,
    ): Effectivity

    fun update(
        actor: Actor,
        effectivityId: UUID,
        request: SetEffectivity,
    ): Effectivity

    fun delete(
        actor: Actor,
        effectivityId: UUID,
    )

    /** The effectivity of these structure lines (of parts the actor can read), for showing beside them. */
    fun forLines(
        actor: Actor,
        lineIds: List<UUID>,
    ): List<Effectivity>

    /** What change notice [changeId] sets (pending), or set (in effect since it was resolved). */
    fun forChange(
        actor: Actor,
        changeId: UUID,
    ): List<Effectivity>
}
