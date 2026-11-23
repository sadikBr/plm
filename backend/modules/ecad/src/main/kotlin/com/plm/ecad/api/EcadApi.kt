package com.plm.ecad.api

import com.plm.platform.actor.Actor
import java.util.UUID

/** One file of a KiCad project. */
class ProjectFile(
    val name: String,
    val bytes: ByteArray,
)

data class EcadSheet(
    val file: String,
    val name: String,
    val components: Int,
)

/** A line of an electronics BOM: the components that are the same part. */
data class EcadBomLine(
    val value: String,
    val footprint: String,
    val partNumber: String?,
    val references: List<String>,
    /** The part it matches in the system (by part number, or by name), if any. */
    val partId: UUID? = null,
    val partLabel: String? = null,
) {
    val quantity: Int get() = references.size
}

data class EcadPreview(
    val project: String,
    val sheets: List<EcadSheet>,
    val board: String?,
    val footprints: Int,
    val copperLayers: Int,
    /** Board size in millimetres. */
    val width: Double,
    val height: Double,
    val lines: List<EcadBomLine>,
)

data class EcadImportOptions(
    val contextId: UUID,
    /** Create a part for each BOM line no existing part matches (else they are listed as missing). */
    val createParts: Boolean = true,
)

data class EcadImported(
    val id: UUID,
    val number: String,
    val name: String,
    val kind: String,
)

data class EcadImportResult(
    val project: EcadImported,
    val documents: List<EcadImported>,
    /** The assembly part (the board as built), with the BOM. */
    val assembly: EcadImported,
    val lines: Int,
    val createdParts: List<EcadImported>,
    val unmatched: List<EcadBomLine>,
)

/** A difference between the schematic's BOM and the assembly part's structure. */
data class BomDifference(
    /** MISSING (in the schematic, not the structure), EXTRA (the other way), QUANTITY, DESIGNATORS. */
    val kind: String,
    val label: String,
    val schematic: List<String>,
    val structure: List<String>,
)

data class BomCheck(
    val assembly: EcadImported?,
    val lines: Int,
    val differences: List<BomDifference>,
)

/**
 * Electrical CAD (roadmap 8.7): KiCad projects as documents (project, schematic sheets, board)
 * with their dependencies, the schematic's components as the board assembly's structure
 * (reference designators as occurrences), a board preview, and a check of the schematic's BOM
 * against the structure. Errors: `NOT_A_KICAD_PROJECT`, `INVALID_KICAD_FILE`, plus versioning's,
 * content's and BOM's.
 */
interface EcadService {
    fun preview(
        actor: Actor,
        files: List<ProjectFile>,
    ): EcadPreview

    fun import(
        actor: Actor,
        files: List<ProjectFile>,
        options: EcadImportOptions,
    ): EcadImportResult

    /** For a project or board document: its schematic's BOM against its assembly part's structure. */
    fun check(
        actor: Actor,
        id: UUID,
    ): BomCheck
}
