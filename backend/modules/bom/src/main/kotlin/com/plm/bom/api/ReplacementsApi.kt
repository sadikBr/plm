package com.plm.bom.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.versioning.api.MasterId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Alternate(
    val part: UsedPart,
    /** True when the other part also has this one as its alternate. */
    val twoWay: Boolean,
    val notes: String?,
    val createdAt: Instant,
    val createdBy: String,
)

data class Substitute(
    val part: UsedPart,
    /** Null: the line's quantity. */
    val quantity: BigDecimal?,
    val notes: String?,
)

data class AddAlternate(
    val alternateId: UUID,
    /** Also make this part an alternate of the other one. */
    val twoWay: Boolean = false,
    val notes: String? = null,
)

data class AddSubstitute(
    val substituteId: UUID,
    val quantity: BigDecimal? = null,
    val notes: String? = null,
)

/** A line using the part as a substitute (for where-used). */
data class SubstituteUse(
    val parent: UsedPart,
    val lineNumber: Int,
    /** The part the line normally uses. */
    val original: UsedPart,
    val quantity: BigDecimal,
    val unit: String,
)

/**
 * Replacements (roadmap 4.8). Alternates replace a part anywhere: they belong to the part (not an
 * iteration) and need MODIFY on it. Substitutes replace the child of one line only: they're part
 * of the structure, edited on the parent's working copy and carried with the line.
 * Errors: `NOT_A_PART`, `INVALID_REPLACEMENT`, `REPLACEMENT_NOT_FOUND`, `NOT_CHECKED_OUT`.
 */
interface ReplacementService {
    fun alternates(
        actor: Actor,
        partId: MasterId,
    ): List<Alternate>

    fun addAlternate(
        actor: Actor,
        partId: MasterId,
        request: AddAlternate,
    ): List<Alternate>

    /** Removes it in both directions. */
    fun removeAlternate(
        actor: Actor,
        partId: MasterId,
        alternateId: UUID,
    )

    /** The substitutes of a line (of any iteration the actor can read). */
    fun substitutes(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
    ): List<Substitute>

    fun addSubstitute(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
        request: AddSubstitute,
    ): List<Substitute>

    fun removeSubstitute(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
        substituteId: UUID,
    )

    /** Lines of latest iterations that list [partId] as a substitute. */
    fun substituteUses(
        actor: Actor,
        partId: MasterId,
    ): List<SubstituteUse>
}

data class ReplacementsChanged(
    val objectId: UUID,
    /** ALTERNATE_ADDED, ALTERNATE_REMOVED, SUBSTITUTE_ADDED, SUBSTITUTE_REMOVED */
    val change: String,
    val otherId: UUID,
    val usageId: UUID?,
) : DomainEvent {
    override val eventType = "bom.ReplacementsChanged.v1"
    override val aggregateType = "versioned-object"
    override val aggregateId: UUID = objectId
}
