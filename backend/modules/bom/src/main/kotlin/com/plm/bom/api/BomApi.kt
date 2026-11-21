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
    /** The iteration chosen for this reading by the configuration specification; null when none matches. */
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
    /** Reference designators of its occurrences (roadmap 4.5), e.g. R1, R2, R3. */
    val designators: List<String>,
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
    /** e.g. `R1-R4, R7`: as many as the quantity (a whole number, unit each). */
    val designators: String? = null,
)

data class UpdateUsage(
    val quantity: BigDecimal,
    val unit: String,
    val lineNumber: Int,
    val findNumber: String? = null,
    val notes: String? = null,
    /** e.g. `R1-R4, R7`; empty or null: none. */
    val designators: String? = null,
)

/**
 * Product structure (roadmap 4.1): a part iteration uses other parts (their masters), with a
 * quantity and unit on numbered lines. Reading needs read access to the parent; lines whose child
 * the reader may not read are kept, without its details. Changing needs the parent checked out to
 * the actor: lines are edited on the working copy, frozen at check-in, and carried forward on
 * check-out and revise. Only parts have structure, and a part can't (indirectly) use itself.
 *
 * Errors: `OBJECT_NOT_FOUND`, `NOT_CHECKED_OUT`, `NOT_A_PART`, `CYCLE`, `USAGE_NOT_FOUND`,
 * `INVALID_USAGE`, `DUPLICATE_LINE_NUMBER`, `INVALID_DESIGNATORS`.
 */
interface BomService {
    /** The lines of [parentId]'s latest iteration, its working copy ([workingCopy]), or [iterationId]. */
    fun usages(
        actor: Actor,
        parentId: MasterId,
        iterationId: UUID? = null,
        workingCopy: Boolean = false,
    ): List<UsageLine>

    /**
     * The structure under [rootId], [depth] levels deep (1 to 50), each part at the iteration [spec]
     * picks; with [workingCopy], the top is the caller's working copy. Parts nobody may read below
     * a placeholder line aren't expanded. At most [limit] lines.
     */
    fun structure(
        actor: Actor,
        rootId: MasterId,
        spec: ConfigSpec = ConfigSpec.LATEST,
        depth: Int = 1,
        workingCopy: Boolean = false,
        limit: Int = 100_000,
    ): StructureResult

    /**
     * Where [childId] is used (roadmap 4.6): the parts whose iteration under [spec] uses it, and
     * up to [depth] levels above them. Parts the reader may not read show as placeholders and
     * aren't followed further up.
     */
    fun whereUsed(
        actor: Actor,
        childId: MasterId,
        spec: ConfigSpec = ConfigSpec.LATEST,
        depth: Int = 1,
        limit: Int = 20_000,
    ): WhereUsedResult

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

    /** Puts another part on a line, keeping its number, quantity and unit (roadmap 4.4). */
    fun replace(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
        childId: UUID,
    ): UsageLine

    /** Renumbers the working copy's lines 10, 20, 30... in this order (all its lines, once each). */
    fun reorder(
        actor: Actor,
        parentId: MasterId,
        usageIds: List<UUID>,
    ): List<UsageLine>

    /**
     * Copies lines from any structure the actor can read (child, quantity, unit, find number,
     * notes) to the end of the working copy (roadmap 4.4). Lines whose child would make a cycle are
     * refused, as a whole.
     */
    fun paste(
        actor: Actor,
        parentId: MasterId,
        usageIds: List<UUID>,
    ): List<UsageLine>
}

/** How a structure picks the iteration of each part it uses (roadmap 4.2). */
enum class ConfigSpecType {
    /** The latest checked-in iteration. */
    LATEST,

    /** The latest iteration in [ConfigSpec.state] (Released by default). */
    STATE,

    /** The latest iteration checked in on or before [ConfigSpec.asOf]. */
    AS_OF,
}

data class ConfigSpec(
    val type: ConfigSpecType = ConfigSpecType.LATEST,
    val state: String? = null,
    val asOf: Instant? = null,
) {
    companion object {
        val LATEST = ConfigSpec()
        val RELEASED = ConfigSpec(ConfigSpecType.STATE, "RELEASED")
    }
}

/** One line of a multi-level structure: where it sits, and the line itself (roadmap 4.3). */
data class StructureNode(
    /** 1 for the top's own lines. */
    val level: Int,
    /** Line numbers from the top, e.g. `10.20.10`: unique within the result. */
    val path: String,
    /** The path of the line above, null at level 1. */
    val parentPath: String?,
    val line: UsageLine,
)

data class StructureResult(
    val rootId: UUID,
    /** The iteration of the top shown, chosen by [spec] (or the caller's working copy). */
    val rootIterationId: UUID?,
    val rootVersion: String?,
    val spec: ConfigSpec,
    val nodes: List<StructureNode>,
    /** True when [StructureNode] count reached the limit and lower levels were left out. */
    val truncated: Boolean,
    val elapsedMs: Long,
)

/** A part that uses the part asked about, directly or higher up (roadmap 4.6). */
data class WhereUsedNode(
    /** 1: uses it directly. */
    val level: Int,
    /** Unique within the result. */
    val path: String,
    val parentPath: String?,
    /** The using part, at the iteration the specification picks. */
    val parent: UsedPart,
    val lineNumber: Int,
    val findNumber: String?,
    val quantity: BigDecimal,
    val unit: String,
    /** Nothing uses it in turn: an end item (or the top of what may be seen). */
    val top: Boolean,
)

data class WhereUsedResult(
    val childId: UUID,
    val spec: ConfigSpec,
    val nodes: List<WhereUsedNode>,
    val truncated: Boolean,
)

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
