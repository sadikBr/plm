package com.plm.change.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

/** The kinds of change object (roadmap 5.8), each a type (and its subtypes). */
enum class ChangeKind { PROBLEM_REPORT, CHANGE_REQUEST, CHANGE_NOTICE, CHANGE_TASK, VARIANCE }

/**
 * How a change object relates to something. AFFECTED, RESULTING and END_ITEM point to parts and
 * documents; ADDRESSES (request → problem report), IMPLEMENTS (notice → request) and TASK
 * (notice → change task) chain change objects.
 */
enum class ChangeRole { AFFECTED, RESULTING, END_ITEM, ADDRESSES, IMPLEMENTS, TASK }

data class LinkedObject(
    val id: UUID,
    val number: String?,
    val name: String?,
    val typeId: UUID?,
    /** Its latest version and state now. */
    val version: String?,
    val state: String?,
    val readable: Boolean,
)

data class ChangeLink(
    val id: UUID,
    val changeId: UUID,
    val role: ChangeRole,
    val target: LinkedObject,
    /** The change object, for links seen from their target. */
    val change: LinkedObject? = null,
    /** The target's version when it was linked. */
    val linkedVersion: String?,
    val note: String?,
    val createdAt: Instant,
    val createdBy: String,
)

/** One task of a change notice's implementation plan (roadmap 5.9). */
data class PlanEntry(
    val task: LinkedObject,
    val assignee: String?,
    val dueDate: java.time.LocalDate?,
    val overdue: Boolean,
)

data class SetPlan(
    /** The assignee's username; null clears it. */
    val assignee: String? = null,
    val dueDate: java.time.LocalDate? = null,
)

/** A change object with everything it's linked to, both ways. */
data class ChangeView(
    val id: UUID,
    val kind: ChangeKind,
    /** A notice's tasks, with who does them by when. */
    val plan: List<PlanEntry> = emptyList(),
    /** Links from it (affected, resulting, end items, its requests, tasks...). */
    val links: List<ChangeLink>,
    /** Change objects linking to it (the request addressing this report, the notice of this task...). */
    val linkedFrom: List<ChangeLink>,
)

data class AddChangeLink(
    val targetId: UUID,
    val role: ChangeRole,
    val note: String? = null,
)

/** Creates the next change object of the chain from [ChangeFrom.fromId] (roadmap 5.8). */
data class ChangeFrom(
    val fromId: UUID,
    val name: String,
    /** CHANGE_REQUEST from a problem report, CHANGE_NOTICE from a request (or a report: fast track), CHANGE_TASK from a notice. */
    val kind: ChangeKind,
    /** For a task: the affected objects it takes on (all of the notice's without). */
    val affected: List<UUID>? = null,
    val typeId: UUID? = null,
)

/**
 * Change objects' relations (roadmap 5.8). Linking needs MODIFY on the change object, in a state
 * where it may be edited (OPEN or IMPLEMENTATION), without a check-out.
 */
interface ChangeService {
    fun kindOf(
        actor: Actor,
        objectId: UUID,
    ): ChangeKind?

    fun view(
        actor: Actor,
        changeId: UUID,
    ): ChangeView

    fun link(
        actor: Actor,
        changeId: UUID,
        request: AddChangeLink,
    ): ChangeLink

    fun unlink(
        actor: Actor,
        changeId: UUID,
        linkId: UUID,
    )

    /** Changes a part or document is affected by, results from, or is an end item of. */
    fun changesOf(
        actor: Actor,
        objectId: UUID,
    ): List<ChangeLink>

    /** Plans one of a notice's tasks: needs MODIFY on the notice; the assignee is told. */
    fun plan(
        actor: Actor,
        noticeId: UUID,
        taskId: UUID,
        request: SetPlan,
    ): PlanEntry

    /** The next change object in the chain, linked and carrying the affected objects and end items. */
    fun createFrom(
        actor: Actor,
        request: ChangeFrom,
    ): UUID
}

data class ChangeLinked(
    val changeId: UUID,
    val targetId: UUID,
    val role: ChangeRole,
    val added: Boolean,
) : DomainEvent {
    override val eventType = "change.ChangeLinked.v1"
    override val aggregateType get() = "change"
    override val aggregateId get() = changeId
}

// ---------- Change templates, association rules and variances (roadmap 5.10) ----------

data class ChangeTemplate(
    val id: UUID,
    val name: String,
    /** The change type it creates. */
    val typeId: UUID,
    /** The new change's name, unless one is given. */
    val title: String?,
    val description: String?,
    /** For a change notice: the change tasks it starts with. */
    val tasks: List<String>,
    val active: Boolean,
)

data class SaveChangeTemplate(
    val name: String,
    val typeId: UUID,
    val title: String? = null,
    val description: String? = null,
    val tasks: List<String> = emptyList(),
    val active: Boolean = true,
)

data class UseChangeTemplate(
    val contextId: UUID,
    val name: String? = null,
)

/** Only these target types (and their subtypes) may be linked to [changeTypeId] changes as [role]. */
data class AssociationRule(
    val id: UUID,
    val changeTypeId: UUID,
    val role: ChangeRole,
    val targetTypeId: UUID,
)

data class SaveAssociationRule(
    val changeTypeId: UUID,
    val role: ChangeRole,
    val targetTypeId: UUID,
)

enum class VarianceKind {
    /** Before making: permission to build differently. */
    DEVIATION,

    /** After making: acceptance of what was built differently. */
    WAIVER,
}

/** A variance's scope: until a date, for a quantity, or both. */
data class VarianceScope(
    val kind: VarianceKind,
    val validUntil: java.time.LocalDate? = null,
    val quantity: Int? = null,
    val reason: String? = null,
)

/** A variance in force on an object: approved, within its date. */
data class ActiveVariance(
    val changeId: UUID,
    val number: String,
    val name: String,
    val scope: VarianceScope,
)

/** Change templates and association rules (site administrators), and variances' scope. */
interface ChangeSetupService {
    fun templates(actor: Actor): List<ChangeTemplate>

    fun saveTemplate(
        actor: Actor,
        id: UUID?,
        request: SaveChangeTemplate,
    ): ChangeTemplate

    fun deleteTemplate(
        actor: Actor,
        id: UUID,
    )

    /** Creates a change from a template (with its tasks, for a notice); returns its id. */
    fun useTemplate(
        actor: Actor,
        id: UUID,
        request: UseChangeTemplate,
    ): UUID

    fun rules(actor: Actor): List<AssociationRule>

    fun addRule(
        actor: Actor,
        request: SaveAssociationRule,
    ): AssociationRule

    fun deleteRule(
        actor: Actor,
        id: UUID,
    )

    fun variance(
        actor: Actor,
        changeId: UUID,
    ): VarianceScope?

    /** Needs MODIFY on the variance, in an editable state. */
    fun setVariance(
        actor: Actor,
        changeId: UUID,
        scope: VarianceScope,
    ): VarianceScope

    /** Variances in force on [objectId]: approved, affecting it, not past their date. */
    fun activeVariances(
        actor: Actor,
        objectId: UUID,
    ): List<ActiveVariance>
}
