package com.plm.cad.api

import com.plm.platform.actor.Actor
import java.util.UUID

/** Why a model is in the collection (roadmap 6.2). */
enum class CollectReason {
    /** The model asked for. */
    SELF,

    /** A model it depends on, required. */
    REQUIRED,

    /** A model it depends on that it can do without. */
    OPTIONAL,

    /** A drawing of a collected model. */
    DRAWING,

    /** An assembly that uses a collected model. */
    PARENT,
}

data class CollectOptions(
    /** Follow dependencies (all levels): none, required ones, or all. */
    val dependencies: String = "REQUIRED",
    /** Add the drawings of the collected models. */
    val drawings: Boolean = true,
    /** Add the assemblies that use the model asked for (one level up). */
    val parents: Boolean = false,
)

data class CollectedModel(
    val model: CadModel,
    val reason: CollectReason,
    /** Who has it checked out, if anyone. */
    val checkedOutBy: String?,
)

enum class CollectorOperation { CHECK_OUT, CHECK_IN, UNDO_CHECK_OUT, REVISE, SET_STATE }

data class CollectorRequest(
    val operation: CollectorOperation,
    val ids: List<UUID>,
    val comment: String? = null,
    /** For SET_STATE. */
    val state: String? = null,
)

data class CollectorResult(
    val id: UUID,
    val number: String,
    val version: String,
    val state: String,
    /** False: it didn't need the operation (already checked out by you, already in the state...). */
    val changed: Boolean,
)

/**
 * The collector (roadmap 6.2): a CAD document with the models it depends on (to any depth), their
 * drawings and the assemblies that use it, and one operation on the chosen ones. The operation is
 * all or nothing: if one model can't take it, none does (the error names it). Errors as for each
 * operation, prefixed by the model's number.
 */
interface CollectorService {
    fun collect(
        actor: Actor,
        id: UUID,
        options: CollectOptions = CollectOptions(),
    ): List<CollectedModel>

    fun apply(
        actor: Actor,
        request: CollectorRequest,
    ): List<CollectorResult>
}
