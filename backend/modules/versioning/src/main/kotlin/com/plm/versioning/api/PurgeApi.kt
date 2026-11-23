package com.plm.versioning.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.util.UUID

/**
 * Removing history for retention (roadmap 9.8), site administrators only. A purge keeps, of every
 * version, its last iteration, the object's latest, and any iteration a baseline or another
 * object's document link points to; the remaining iterations are re-chained past the removed ones.
 * Removing a whole object (after archiving it) skips the delete rules but not the operation guards
 * (legal holds refuse it). Errors: `ADMIN_REQUIRED`, `OPERATION_REFUSED`, `CHECKED_OUT`.
 */
interface PurgeService {
    /** The iterations a purge of [id] would remove. */
    fun purgeable(
        actor: Actor,
        id: MasterId,
    ): List<Iteration>

    /** Removes them; returns how many. */
    fun purgeIterations(
        actor: Actor,
        id: MasterId,
    ): Int

    /** Removes the object and all its history. */
    fun purgeObject(
        actor: Actor,
        id: MasterId,
    )
}

data class IterationsPurged(
    val objectId: UUID,
    val number: String,
    /** The versions removed, e.g. A.1, A.2. */
    val versions: List<String>,
) : DomainEvent {
    override val eventType = "versioning.IterationsPurged.v1"
    override val aggregateType = "versioned-object"
    override val aggregateId = objectId
}
