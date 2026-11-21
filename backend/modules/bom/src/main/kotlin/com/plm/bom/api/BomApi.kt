package com.plm.bom.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.versioning.api.MasterId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** The child a line points to, as the reader sees it; null fields when they may not read it. */
data class UsedPart(
    val id: UUID,
    /** False: the reader may not see it (the line still counts). */
    val readable: Boolean,
    val number: String?,
    val name: String?,
    val typeId: UUID?,
    /** The iteration chosen for this reading: the latest, until configuration specifications (4.2). */
    val iterationId: UUID?,
    val version: String?,
    val state: String?,
    /** Whether the child has children of its own (for expanding). */
    val hasChildren: Boolean,
)

/** One line of a parent's structure (roadmap 4.1). */
data class UsageLine(
    val id: UUID,
    val parentIterationId: UUID,
    val lineNumber: Int,
    val findNumber: String?,
    val quantity: BigDecimal,
    /** A key of the `unitOfUse` value list. */
    val unit: String,
    val notes: String?,
    val child: UsedPart,
    val createdAt: Instant,
    val createdBy: String,
)

data class AddUsage(
    val childId: UUID,
    val quantity: BigDecimal = BigDecimal.ONE,
    /** Defaults to the child's default unit, else `each`. */
    val unit: String? = null,
    /** Defaults to the next multiple of 10. */
    val lineNumber: Int? = null,
    val findNumber: String? = null,
    val notes: String? = null,
)

data class UpdateUsage(
    val quantity: BigDecimal,
    val unit: String,
    val lineNumber: Int,
    val findNumber: String? = null,
    val notes: String? = null,
)

/**
 * Product structure (roadmap 4.1): a part iteration uses other parts (their masters), with a
 * quantity and unit on numbered lines. Reading needs read access to the parent; lines whose child
 * the reader may not read are kept, without its details. Changing needs the parent checked out to
 * the actor: lines are edited on the working copy, frozen at check-in, and carried forward on
 * check-out and revise. Only parts have structure, and a part can't (indirectly) use itself.
 *
 * Errors: `OBJECT_NOT_FOUND`, `NOT_CHECKED_OUT`, `NOT_A_PART`, `CYCLE`, `USAGE_NOT_FOUND`,
 * `INVALID_USAGE`, `DUPLICATE_LINE_NUMBER`.
 */
interface BomService {
    /** The lines of [parentId]'s latest iteration, its working copy ([workingCopy]), or [iterationId]. */
    fun usages(
        actor: Actor,
        parentId: MasterId,
        iterationId: UUID? = null,
        workingCopy: Boolean = false,
    ): List<UsageLine>

    fun add(
        actor: Actor,
        parentId: MasterId,
        usage: AddUsage,
    ): UsageLine

    fun update(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
        usage: UpdateUsage,
    ): UsageLine

    fun remove(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
    )
}

/** A change to a working copy's structure, recorded on the parent's audit trail. */
data class StructureChanged(
    val objectId: UUID,
    val iterationId: UUID,
    /** ADDED, UPDATED, REMOVED */
    val change: String,
    val lineNumber: Int,
    val childId: UUID,
    val childNumber: String?,
    val quantity: BigDecimal?,
    val unit: String?,
) : DomainEvent {
    override val eventType = "bom.StructureChanged.v1"
    override val aggregateType = "versioned-object"
    override val aggregateId: UUID = objectId
}
