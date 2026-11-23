package com.plm.workspace.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** Where a workspace file stands, between the person's computer and the system (roadmap 8.1). */
enum class FileState {
    /** Their computer has the latest file and hasn't changed it. */
    IN_SYNC,

    /** A newer iteration is in the system: update before editing. */
    OUT_OF_DATE,

    /** Changed on their computer, not yet uploaded. */
    MODIFIED,

    /** Checked out by them; their changes, if any, are uploaded. */
    CHECKED_OUT,

    /** Changed on their computer while someone checked in a newer iteration: decide which wins. */
    CONFLICT,

    /** Someone else has it checked out: read only. */
    CHECKED_OUT_BY_OTHER,

    /** They can no longer read it (access changed, or it was deleted). */
    UNAVAILABLE,
}

data class WorkspaceFile(
    val contentId: UUID,
    val fileName: String,
    val size: Long,
    val sha256: String,
)

data class WorkspaceItem(
    val objectId: UUID,
    val number: String,
    val name: String,
    val typeId: UUID?,
    val version: String?,
    val state: String?,
    val fileState: FileState,
    /** The latest checked-in iteration's primary file. */
    val file: WorkspaceFile?,
    /** Their working copy's primary file, when they have it checked out. */
    val workingFile: WorkspaceFile?,
    /** The iteration their computer holds. */
    val baseVersion: String?,
    val checkedOutBy: String?,
    /** The CAD kind (PART, ASSEMBLY, DRAWING) for CAD documents. */
    val cadKind: String?,
    val addedAt: Instant,
)

/** A file from their computer that isn't an object yet. */
data class NewFile(
    val id: UUID,
    val fileName: String,
    val size: Long?,
    val addedAt: Instant,
)

data class WorkspaceEvent(
    val at: Instant,
    val kind: String,
    val objectId: UUID?,
    val number: String?,
    val detail: String?,
)

data class Workspace(
    val id: UUID,
    val name: String,
    val contextId: UUID,
    val folderId: UUID?,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Items by file state (only in lists: the summary). */
    val counts: Map<FileState, Int> = emptyMap(),
)

data class WorkspaceDetail(
    val workspace: Workspace,
    val items: List<WorkspaceItem>,
    val newFiles: List<NewFile>,
)

data class CreateWorkspace(
    val name: String,
    val contextId: UUID,
    val folderId: UUID? = null,
    val description: String? = null,
)

data class AddToWorkspace(
    val ids: List<UUID>,
    /** For CAD documents: add what they depend on and their drawings too (the collector). */
    val dependencies: Boolean = true,
)

/** What their computer has, as a sync client reports it. */
data class LocalFile(
    val objectId: UUID,
    /** The iteration the file came from. */
    val iterationId: UUID?,
    /** The file's SHA-256 now; null when it was deleted locally. */
    val sha256: String?,
)

data class ReportLocal(
    val files: List<LocalFile>,
)

data class WorkspaceOperation(
    /** Empty: every item it applies to (check in: everything they have checked out here). */
    val ids: List<UUID> = emptyList(),
    val comment: String? = null,
)

/** A changed file sent for an item: it goes into their working copy, checking it out if needed. */
data class UploadChange(
    val objectId: UUID,
    val uploadId: UUID,
)

data class AddNewFile(
    val uploadId: UUID,
    val fileName: String,
    val size: Long? = null,
)

/** Turns a new file into an object (a document, or a CAD document for CAD files). */
data class CreateFromFile(
    val typeId: UUID,
    val name: String,
    val number: String? = null,
)

data class OperationResult(
    val objectId: UUID,
    val number: String,
    val version: String?,
    val changed: Boolean,
)

/** What a client should download to bring an item up to date. */
data class Download(
    val objectId: UUID,
    val number: String,
    val iterationId: UUID,
    val version: String,
    val fileName: String,
    val sha256: String,
    val url: String,
)

/**
 * Personal workspaces (roadmap 8.1). A workspace belongs to its owner; nobody else sees it. Items
 * are objects the owner can read; operations need what the operation needs (MODIFY to check out).
 * Check out, check in and undo on several items are all or nothing: if one can't, none does, and
 * the error names it.
 *
 * Errors: `WORKSPACE_NOT_FOUND`, `DUPLICATE_WORKSPACE`, `INVALID_NAME`, `NOT_IN_WORKSPACE`,
 * `FILE_NOT_FOUND`, plus those of the versioning and content operations, prefixed by the number.
 */
interface WorkspaceService {
    fun list(actor: Actor): List<Workspace>

    fun create(
        actor: Actor,
        command: CreateWorkspace,
    ): Workspace

    fun update(
        actor: Actor,
        id: UUID,
        command: CreateWorkspace,
    ): Workspace

    fun delete(
        actor: Actor,
        id: UUID,
    )

    fun get(
        actor: Actor,
        id: UUID,
    ): WorkspaceDetail

    fun add(
        actor: Actor,
        id: UUID,
        command: AddToWorkspace,
    ): WorkspaceDetail

    fun remove(
        actor: Actor,
        id: UUID,
        ids: List<UUID>,
    ): WorkspaceDetail

    fun reportLocal(
        actor: Actor,
        id: UUID,
        report: ReportLocal,
    ): WorkspaceDetail

    /** Links to bring the chosen items (default: those out of date or in conflict) up to date. */
    fun downloads(
        actor: Actor,
        id: UUID,
        ids: List<UUID> = emptyList(),
    ): List<Download>

    fun checkOut(
        actor: Actor,
        id: UUID,
        op: WorkspaceOperation,
    ): List<OperationResult>

    fun checkIn(
        actor: Actor,
        id: UUID,
        op: WorkspaceOperation,
    ): List<OperationResult>

    fun undo(
        actor: Actor,
        id: UUID,
        op: WorkspaceOperation,
    ): List<OperationResult>

    fun upload(
        actor: Actor,
        id: UUID,
        change: UploadChange,
    ): WorkspaceItem

    fun addNewFile(
        actor: Actor,
        id: UUID,
        file: AddNewFile,
    ): NewFile

    fun removeNewFile(
        actor: Actor,
        id: UUID,
        fileId: UUID,
    )

    fun createFromFile(
        actor: Actor,
        id: UUID,
        fileId: UUID,
        command: CreateFromFile,
    ): WorkspaceItem

    fun history(
        actor: Actor,
        id: UUID,
    ): List<WorkspaceEvent>

    /** A zip of the items' latest files (their working copies when checked out by the owner). */
    fun zip(
        actor: Actor,
        id: UUID,
        out: java.io.OutputStream,
    )
}
