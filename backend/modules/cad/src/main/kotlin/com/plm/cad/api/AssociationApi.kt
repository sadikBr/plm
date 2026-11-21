package com.plm.cad.api

import com.plm.platform.actor.Actor
import java.math.BigDecimal
import java.util.UUID

enum class AssociationKind {
    /** The model the part is: its geometry, and its structure when built. */
    OWNER,

    /** A model that adds to the part (a weld, a skeleton) without owning it. */
    CONTRIBUTING,
}

data class PartRef(
    val id: UUID,
    val readable: Boolean,
    val number: String?,
    val name: String?,
    val version: String?,
    val state: String?,
)

data class CadAssociation(
    val id: UUID,
    val kind: AssociationKind,
    val part: PartRef,
    val model: CadModel,
)

data class Associate(
    val cadId: UUID,
    val kind: AssociationKind = AssociationKind.OWNER,
)

data class BuildRequest(
    /** Make a part for each component that has none (named after it, owned by it). */
    val createParts: Boolean = false,
)

data class BuiltLine(
    val part: PartRef,
    val quantity: BigDecimal,
    /** ADDED, UPDATED, UNCHANGED, REMOVED or KEPT (added by hand). */
    val change: String,
)

data class BuildResult(
    val model: CadModel,
    val lines: List<BuiltLine>,
    /** Components with no part: they aren't in the structure. */
    val missing: List<CadModel>,
    /** Parts made for components that had none. */
    val created: List<PartRef>,
)

/**
 * Part ↔ CAD association and build (roadmap 6.4). Associations belong to the part's version (edited
 * on its working copy, kept in new versions). Building reads the owning model's components (one
 * level) and makes the part's structure match: a line per component part with as many as it has
 * occurrences; lines of parts that came from CAD but left the model are removed, lines of parts
 * without a model (added by hand) are kept. Errors: `NOT_A_PART`, `NOT_A_CAD_DOCUMENT`,
 * `INVALID_ASSOCIATION`, `NO_OWNING_MODEL`, `NOT_CHECKED_OUT`.
 */
interface AssociationService {
    /** A part's models, or a model's parts (latest versions). */
    fun associations(
        actor: Actor,
        id: UUID,
        workingCopy: Boolean = false,
    ): List<CadAssociation>

    fun associate(
        actor: Actor,
        partId: UUID,
        request: Associate,
    ): CadAssociation

    fun dissociate(
        actor: Actor,
        partId: UUID,
        associationId: UUID,
    )

    /** Rebuilds the part's working copy's structure from its owning model. */
    fun build(
        actor: Actor,
        partId: UUID,
        request: BuildRequest = BuildRequest(),
    ): BuildResult
}
