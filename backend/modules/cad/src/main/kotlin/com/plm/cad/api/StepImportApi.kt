package com.plm.cad.api

import com.plm.platform.actor.Actor
import java.util.UUID

/** One line of a STEP file's structure, as it would be imported. */
data class StepLine(
    val depth: Int,
    val name: String,
    val partNumber: String,
    val kind: CadKind,
    val occurrence: String?,
    /** Position in its parent, millimetres. */
    val position: List<Double>?,
)

data class StepPreview(
    val fileName: String,
    val schema: String,
    /** Millimetres per unit of the file. */
    val unitToMm: Double,
    val models: Int,
    val assemblies: Int,
    val lines: List<StepLine>,
)

data class StepImportOptions(
    val contextId: UUID,
    /** Make a part for each model, owned by it. */
    val createParts: Boolean = true,
    /** Build each assembly part's structure from its model. */
    val build: Boolean = true,
)

data class ImportedObject(
    val id: UUID,
    val number: String,
    val name: String,
    val kind: String,
)

data class StepImportResult(
    /** The top model, which holds the file. */
    val rootId: UUID,
    val models: List<ImportedObject>,
    val parts: List<ImportedObject>,
)

/**
 * STEP import (roadmap 6.5): a STEP file (AP203, AP214, AP242) becomes CAD documents, one per
 * product, with their assembly structure and placements; the file is the top model's primary
 * content. Optionally, a part per model, owned by it, with assemblies' structures built.
 * Errors: `INVALID_STEP`, `STEP_TOO_LARGE`, `NO_PRODUCT`.
 */
interface StepImportService {
    fun preview(
        actor: Actor,
        fileName: String,
        bytes: ByteArray,
    ): StepPreview

    fun import(
        actor: Actor,
        fileName: String,
        bytes: ByteArray,
        options: StepImportOptions,
    ): StepImportResult
}
