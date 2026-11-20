package com.plm.versioning.api

import com.plm.access.api.ContextId
import com.plm.platform.actor.Actor
import com.plm.types.api.TypeId
import java.time.Instant
import java.util.UUID

@JvmInline
value class MasterId(
    val value: UUID,
) {
    override fun toString() = value.toString()
}

@JvmInline
value class IterationId(
    val value: UUID,
) {
    override fun toString() = value.toString()
}

enum class IterationStatus {
    /** Immutable, part of the object's history. */
    CHECKED_IN,

    /** The working copy of a checked-out object. Only its owner can see or change it. */
    WORKING,
}

data class Iteration(
    val id: IterationId,
    val masterId: MasterId,
    val revision: String,
    val iteration: Int,
    val status: IterationStatus,
    val state: String,
    val attributes: Map<String, Any?>,
    val basedOnId: IterationId?,
    val comment: String?,
    val createdAt: Instant,
    val createdBy: String,
    val checkedInAt: Instant?,
    val checkedOutBy: String?,
) {
    /** Display form, e.g. `B.3`. */
    val version: String get() = "$revision.$iteration"
}

data class VersionedObject(
    val id: MasterId,
    val typeId: TypeId,
    val contextId: ContextId,
    val number: String,
    val name: String,
    /** Latest checked-in iteration. A working copy is never "latest". */
    val latest: Iteration,
    /** Who has the object checked out, or null. */
    val checkedOutBy: String?,
    val createdAt: Instant,
    val createdBy: String,
)

/**
 * Which objects to list. Everything is optional: no filter means every object the actor can read.
 * [text] matches number or name, case-insensitively, anywhere in the string.
 */
data class ObjectQuery(
    val contextId: ContextId? = null,
    val text: String? = null,
    val limit: Int = 50,
    /** Keyset paging: the last id of the previous page. */
    val after: MasterId? = null,
)

data class CreateVersionedObject(
    val typeId: TypeId,
    val contextId: ContextId,
    val name: String,
    /** Leave null to auto-assign the next number. */
    val number: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
)

/**
 * The generic versioning engine shared by every versioned type (documents, parts, CAD documents,
 * change objects).
 *
 * Access (ADR 0005): reading needs READ, which is evaluated on the latest iteration's state.
 * Objects the actor cannot read are reported as `OBJECT_NOT_FOUND`, so their existence doesn't
 * leak. Create needs CREATE in the context. Check-out, edit, check-in and undo need MODIFY.
 * Revise needs REVISE. A missing permission on a readable object is `ACCESS_DENIED`.
 *
 * Error codes: `OBJECT_NOT_FOUND`, `ACCESS_DENIED`, `DUPLICATE_NUMBER`, `INVALID_NAME`,
 * `INVALID_NUMBER`, `INVALID_ATTRIBUTES`, `INVALID_CONTEXT`, `ALREADY_CHECKED_OUT`,
 * `NOT_CHECKED_OUT`, `CHECKED_OUT_BY_ANOTHER_USER`, `CHECKED_OUT`.
 *
 * Every change publishes a [VersioningEvent].
 */
interface VersioningService {
    /** Creates the master and its first iteration (A.1), checked in. */
    fun create(
        actor: Actor,
        command: CreateVersionedObject,
    ): VersionedObject

    fun get(
        actor: Actor,
        id: MasterId,
    ): VersionedObject

    /**
     * Objects in [contextId] that [actor] can read, ordered by id (creation time). The access
     * filter runs inside the SQL query. Page with [after] = the last id of the previous page.
     */
    fun list(
        actor: Actor,
        query: ObjectQuery,
    ): List<VersionedObject>

    /** Checked-in iterations, newest first. */
    fun history(
        actor: Actor,
        id: MasterId,
    ): List<Iteration>

    /** The actor's own working copy. */
    fun workingCopy(
        actor: Actor,
        id: MasterId,
    ): Iteration

    /** Creates a working copy of the latest iteration. Its number is the next iteration (A.1 -> A.2). */
    fun checkOut(
        actor: Actor,
        id: MasterId,
    ): Iteration

    /** Replaces the working copy's attributes. */
    fun updateWorkingCopy(
        actor: Actor,
        id: MasterId,
        attributes: Map<String, Any?>,
    ): Iteration

    /** Freezes the working copy as a new immutable iteration and makes it the latest. */
    fun checkIn(
        actor: Actor,
        id: MasterId,
        comment: String? = null,
    ): VersionedObject

    /** Discards the working copy. */
    fun undoCheckOut(
        actor: Actor,
        id: MasterId,
    ): VersionedObject

    /** Starts the next revision from the latest iteration (A.3 -> B.1). */
    fun revise(
        actor: Actor,
        id: MasterId,
    ): VersionedObject
}
