package com.plm.bulk.api

import com.plm.jobs.api.Job
import com.plm.platform.actor.Actor
import java.util.UUID

/** An uploaded file: the spreadsheet, or the zip with the files it names. */
class LoadFile(
    val name: String,
    val bytes: ByteArray,
)

enum class LoadAction {
    /** A new object. */
    CREATE,

    /** An existing object (same number): its attributes, file, state and links change. */
    UPDATE,

    /** Nothing to do. */
    UNCHANGED,

    /** The row has errors: nothing is loaded until they are fixed. */
    ERROR,
}

/** How a column of the spreadsheet is understood. */
data class LoadColumn(
    val header: String,
    /** type, number, name, folder, file, parent, quantity, findNumber, documents, state, attribute, ignored. */
    val meaning: String,
    /** For an attribute column: the attribute's name (it may differ per type). */
    val attribute: String? = null,
)

data class LoadRow(
    /** The spreadsheet's row number (the header is row 1). */
    val row: Int,
    val action: LoadAction,
    val type: String?,
    val number: String?,
    val name: String?,
    /** The existing object, for an update. */
    val objectId: UUID? = null,
    val folder: String? = null,
    val file: String? = null,
    val parent: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** What a load would do (the dry run). */
data class LoadPreview(
    val fileName: String,
    val contextId: UUID,
    val contextName: String,
    val columns: List<LoadColumn>,
    val rows: List<LoadRow>,
    val creates: Int,
    val updates: Int,
    val errors: Int,
    /** Files from the zip that become primary files. */
    val files: Int,
    /** Structure lines (parent → child). */
    val usages: Int,
    /** Part–document links. */
    val links: Int,
    /** Folders that will be made. */
    val newFolders: List<String>,
)

/**
 * Bulk load (roadmap 9.2): objects of any type from a spreadsheet (CSV or Excel), with
 * attributes (by name or label), folders (`/Drawings/Brackets`, made when missing), files from a
 * zip, structure (a Parent column with quantity and find number) and part–document links (a
 * Documents column on part rows). A row whose number exists updates that object. [preview] is the
 * dry run: what each row would do, and why a row can't be loaded. [start] runs the load as a
 * background job that creates and updates the objects, then the structure and links, with a
 * result per row; nothing starts while any row has errors.
 *
 * Errors: `INVALID_IMPORT_FILE`, `INVALID_LOAD` (rows with errors), plus access's.
 */
interface BulkLoadService {
    fun preview(
        actor: Actor,
        contextId: UUID,
        sheet: LoadFile,
        archive: LoadFile?,
    ): LoadPreview

    fun start(
        actor: Actor,
        contextId: UUID,
        sheet: LoadFile,
        archive: LoadFile?,
    ): Job

    /** A spreadsheet to fill in for [typeId]: the fixed columns and the type's attributes. */
    fun template(
        actor: Actor,
        typeId: UUID,
    ): String
}
