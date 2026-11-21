package com.plm.change.api

import com.plm.platform.actor.Actor
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** What a redline does (roadmap 5.11). */
enum class RedlineOp {
    /** Adds a line using [Redline.child] to the target's structure. */
    ADD,

    /** Removes the lines using [Redline.child]. */
    REMOVE,

    /** Sets the quantity of the lines using [Redline.child]. */
    QUANTITY,

    /** Puts [Redline.newChild] on the lines using [Redline.child]. */
    REPLACE,

    /** Sets an attribute of the target. */
    SET_ATTRIBUTE,
}

/** An edit a change proposes; applied when the change is resolved (roadmap 5.11). */
data class Redline(
    val id: UUID,
    val changeId: UUID,
    val op: RedlineOp,
    val target: LinkedObject,
    val child: LinkedObject?,
    val newChild: LinkedObject?,
    val quantity: BigDecimal?,
    val attribute: String?,
    val value: Any?,
    val note: String?,
    val createdAt: Instant,
    val createdBy: String,
    /** When it was applied; null while proposed. */
    val appliedAt: Instant?,
    /** The target's version it produced. */
    val appliedVersion: String?,
)

data class AddRedline(
    val op: RedlineOp,
    val targetId: UUID,
    val childId: UUID? = null,
    val newChildId: UUID? = null,
    val quantity: BigDecimal? = null,
    val attribute: String? = null,
    val value: Any? = null,
    val note: String? = null,
)

/**
 * One edit across a change's affected objects (roadmap 5.11): for each one it applies to, a
 * redline. [RedlineOp.ADD] is not a mass change.
 */
data class MassChange(
    val op: RedlineOp,
    val childId: UUID? = null,
    val newChildId: UUID? = null,
    val quantity: BigDecimal? = null,
    val attribute: String? = null,
    val value: Any? = null,
    val note: String? = null,
)

data class MassChangeSkip(
    val objectId: UUID,
    val number: String?,
    val reason: String,
)

data class MassChangeResult(
    val created: List<Redline>,
    val skipped: List<MassChangeSkip>,
)

/**
 * Redlines (roadmap 5.11): a change (a task or a notice, usually) proposes structure and attribute
 * edits without checking anything out. When the change is resolved, each target is checked out
 * (revised first when released), edited and checked in, and becomes a resulting object of the
 * notice; a redline that can't be applied refuses the resolution.
 *
 * Errors: `CHANGE_NOT_EDITABLE`, `INVALID_REDLINE`, `REDLINE_NOT_FOUND`.
 */
interface RedlineService {
    fun list(
        actor: Actor,
        changeId: UUID,
    ): List<Redline>

    fun add(
        actor: Actor,
        changeId: UUID,
        request: AddRedline,
    ): Redline

    fun remove(
        actor: Actor,
        changeId: UUID,
        redlineId: UUID,
    )

    fun mass(
        actor: Actor,
        changeId: UUID,
        request: MassChange,
    ): MassChangeResult
}
