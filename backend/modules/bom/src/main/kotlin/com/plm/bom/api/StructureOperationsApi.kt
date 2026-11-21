package com.plm.bom.api

import com.plm.jobs.api.Job
import com.plm.platform.actor.Actor
import java.util.UUID

/** Puts [newId] in place of [oldId] in the structure of each of [parentIds] (roadmap 4.14). */
data class MassReplace(
    val oldId: UUID,
    val newId: UUID,
    val parentIds: List<UUID>,
    /** Revise a parent that can't be checked out in its state (released) instead of skipping it. */
    val reviseReleased: Boolean = false,
    val comment: String? = null,
)

/** Structure operations on many objects at once, run as background jobs with a result per object. */
interface StructureOperationsService {
    /**
     * Each parent is checked out (revised first if asked and needed), every line using the old part
     * gets the new one (keeping line, find number, quantity and unit), and checked in; each in its
     * own transaction, so one failing parent changes nothing of its own and doesn't stop the rest.
     */
    fun replaceEverywhere(
        actor: Actor,
        request: MassReplace,
    ): Job
}
