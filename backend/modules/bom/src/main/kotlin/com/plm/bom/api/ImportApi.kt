package com.plm.bom.api

import com.plm.jobs.api.Job
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import java.math.BigDecimal
import java.util.UUID

/**
 * One line of a structure file (roadmap 4.13), as read: [level] 1 is used directly by the part
 * the file is imported into, 2 by the line above it at level 1, and so on. A part is found by its
 * [number]; without one (or with a number that doesn't exist yet) it is created, named [name].
 */
data class ImportRow(
    /** The row in the file, from 2 (row 1 is the header). */
    val row: Int,
    val level: Int,
    val number: String? = null,
    val name: String? = null,
    val quantity: BigDecimal? = null,
    val unit: String? = null,
    val findNumber: String? = null,
    val designators: String? = null,
    val notes: String? = null,
)

enum class ImportAction {
    /** An existing part. */
    MATCH,

    /** A new part, created by the import. */
    CREATE,
}

data class PreviewRow(
    val row: ImportRow,
    val action: ImportAction?,
    /** The existing part it matched. */
    val partId: UUID?,
    val partNumber: String?,
    val partName: String?,
    /** Its structure is (re)defined by the lines under it in the file. */
    val assembly: Boolean,
    /** Stop the import. */
    val errors: List<String>,
    /** Worth knowing; don't stop it. */
    val warnings: List<String>,
)

data class ImportPreview(
    val rootId: UUID,
    val rootNumber: String,
    val rows: List<PreviewRow>,
    /** Distinct parts the import will create. */
    val creates: Int,
    /** Parts whose structure the import will set: the root and every assembly in the file. */
    val assemblies: Int,
    val errors: Int,
)

/**
 * Structure import (roadmap 4.13). The file defines, for the root and for each part with lines
 * under it, its whole single-level structure: lines are matched to existing ones by child (and
 * find number), updated, added or removed. Parts that don't exist are created first. [preview]
 * changes nothing; [start] runs the import as a background job, assembly by assembly.
 */
interface StructureImportService {
    /** Reads a CSV (comma or semicolon) or Excel (.xlsx) file. */
    fun read(
        fileName: String,
        bytes: ByteArray,
    ): List<ImportRow>

    fun preview(
        actor: Actor,
        rootId: MasterId,
        rows: List<ImportRow>,
    ): ImportPreview

    /** @throws com.plm.platform.error.ValidationException `INVALID_IMPORT` while the preview has errors */
    fun start(
        actor: Actor,
        rootId: MasterId,
        rows: List<ImportRow>,
        fileName: String?,
    ): Job
}

/** A structure line of a past iteration (roadmap 9.3). */
data class PastUsage(
    val childId: UUID,
    val quantity: BigDecimal,
    /** A unitOfUse key; `each` when null. */
    val unit: String? = null,
    val findNumber: String? = null,
    val notes: String? = null,
)

/**
 * Structure and part–document links of past iterations, for migrations (roadmap 9.3): written
 * onto the iteration as it was, without a new one. Site administrators only.
 * Errors: `ADMIN_REQUIRED`, `NOT_A_PART`, `NOT_A_DOCUMENT`, `INVALID_USAGE`.
 */
interface StructureHistoryImportService {
    fun importUsages(
        actor: Actor,
        parentId: MasterId,
        parentIterationId: UUID,
        lines: List<PastUsage>,
    )

    /** DESCRIBED_BY points at the document's latest iteration. */
    fun importDocumentLink(
        actor: Actor,
        partId: MasterId,
        partIterationId: UUID,
        documentId: MasterId,
        kind: DocumentLinkKind,
    )
}
