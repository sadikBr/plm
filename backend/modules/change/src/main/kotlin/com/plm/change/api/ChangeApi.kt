package com.plm.change.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

/** The kinds of change object (roadmap 5.8), each a type (and its subtypes). */
enum class ChangeKind { PROBLEM_REPORT, CHANGE_REQUEST, CHANGE_NOTICE, CHANGE_TASK }

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

/** A change object with everything it's linked to, both ways. */
data class ChangeView(
    val id: UUID,
    val kind: ChangeKind,
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
