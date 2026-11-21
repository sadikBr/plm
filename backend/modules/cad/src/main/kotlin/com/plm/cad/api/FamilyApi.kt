package com.plm.cad.api

import com.plm.platform.actor.Actor
import java.util.UUID

data class FamilyColumn(
    /** The parameter's name in the model, e.g. `length`. */
    val name: String,
    val label: String? = null,
    val unit: String? = null,
)

data class FamilyInstance(
    val model: CadModel,
    /** By column name. */
    val values: Map<String, Any?>,
)

/** A generic model's family table (roadmap 6.3), at one of its versions. */
data class FamilyTable(
    val generic: CadModel,
    val genericVersion: String,
    val columns: List<FamilyColumn>,
    val instances: List<FamilyInstance>,
)

/** What a model is in a family: its generic's table, if it's the generic or one of its instances. */
data class FamilyMembership(
    /** GENERIC, INSTANCE or NONE. */
    val role: String,
    val table: FamilyTable?,
)

data class NewInstance(
    val name: String,
    val values: Map<String, Any?> = emptyMap(),
    /** Its number, where manual numbers are allowed; otherwise the next one. */
    val number: String? = null,
)

/**
 * Family tables (roadmap 6.3). A CAD part or assembly becomes a generic by having columns; each
 * instance is a CAD document of the generic's type, in its context, sharing its file, with one
 * value per column. The table belongs to the generic's version: edited on its working copy
 * (`NOT_CHECKED_OUT`), carried into new versions. Errors: `INVALID_FAMILY`, `INSTANCE_NOT_FOUND`.
 */
interface FamilyService {
    fun membership(
        actor: Actor,
        id: UUID,
    ): FamilyMembership

    /** The table of [genericId]'s latest version, or of the caller's working copy. */
    fun table(
        actor: Actor,
        genericId: UUID,
        workingCopy: Boolean = false,
    ): FamilyTable

    fun setColumns(
        actor: Actor,
        genericId: UUID,
        columns: List<FamilyColumn>,
    ): FamilyTable

    /** Creates the instance's CAD document and its row. */
    fun addInstance(
        actor: Actor,
        genericId: UUID,
        instance: NewInstance,
    ): FamilyTable

    fun setValues(
        actor: Actor,
        genericId: UUID,
        instanceId: UUID,
        values: Map<String, Any?>,
    ): FamilyTable

    /** Takes the instance out of the table (its document stays). */
    fun removeInstance(
        actor: Actor,
        genericId: UUID,
        instanceId: UUID,
    ): FamilyTable
}
