package com.plm.versioning.api

import com.plm.access.api.ContextId
import com.plm.platform.actor.Actor
import com.plm.types.api.TypeId
import java.time.Instant
import java.util.UUID

/** One iteration of an object's past, as another system recorded it. */
data class PastIteration(
    /** The version label as it was (`A`, `02`, `B`). */
    val revision: String,
    val iteration: Int,
    /** A state of the type's lifecycle. */
    val state: String,
    val attributes: Map<String, Any?>,
    val comment: String?,
    val createdAt: Instant,
    /** Who made it (a principal id). */
    val createdBy: String,
)

data class ImportObjectHistory(
    val typeId: TypeId,
    val contextId: ContextId,
    /** Kept as it was. */
    val number: String,
    val name: String,
    val folderId: UUID? = null,
    /** Oldest first; the last one becomes the latest. */
    val iterations: List<PastIteration>,
)

data class ImportedHistory(
    val id: MasterId,
    /** The new iterations' ids, in the order given. */
    val iterationIds: List<IterationId>,
)

/**
 * Brings in an object with its history from another system (roadmap 9.3): every iteration with
 * its own version label, date, author, state and attributes, all checked in. Site administrators
 * only; numbers and versions are kept as they were. Attributes are validated as on any check-in.
 *
 * Errors: `ADMIN_REQUIRED`, `INVALID_HISTORY`, `DUPLICATE_NUMBER`, `INVALID_ATTRIBUTES`,
 * `INVALID_CONTEXT`, `TYPE_NOT_INSTANTIABLE`.
 */
interface HistoryImportService {
    fun importObject(
        actor: Actor,
        command: ImportObjectHistory,
    ): ImportedHistory
}
