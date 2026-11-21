package com.plm.versioning.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

data class Folder(
    val id: UUID,
    val contextId: UUID,
    val parentId: UUID?,
    val name: String,
    val folders: Int,
    val objects: Int,
    val createdAt: Instant,
    val createdBy: String,
)

data class MoveObject(
    val contextId: UUID,
    /** Null: the top of the context. */
    val folderId: UUID? = null,
)

enum class Deleter {
    /** The object's owner. */
    OWNER,

    /** Managers of its context (MANAGE). */
    MANAGERS,
}

/** What may be deleted, and by whom. Site administrators always may. Never while checked out. */
data class DeleteRules(
    /** Lifecycle states objects may be deleted in; empty: any. */
    val states: List<String> = listOf("IN_WORK"),
    val who: Set<Deleter> = setOf(Deleter.OWNER, Deleter.MANAGERS),
)

/**
 * Folders and keeping objects in order (roadmap 3.4): folders in contexts, moving objects between
 * folders and contexts, deleting by the delete rules, owners, and reservations.
 *
 * Folders: any member can see them; creating needs CREATE in the context, renaming and deleting
 * (empty folders only) need MANAGE. Moving an object needs MODIFY on it and CREATE where it goes;
 * extension rules can refuse moves and deletes (`object.move`, `object.delete`).
 *
 * Error codes: `FOLDER_NOT_FOUND`, `DUPLICATE_FOLDER`, `INVALID_FOLDER`, `FOLDER_NOT_EMPTY`,
 * `ACCESS_DENIED`, `CHECKED_OUT`, `DELETE_NOT_ALLOWED`, `RESERVED_BY_ANOTHER_USER`, `NOT_RESERVED`,
 * `PRINCIPAL_NOT_FOUND`.
 */
interface OrganizingService {
    /** Sub-folders of [parentId] (the context's top folders when null), by name. */
    fun folders(
        actor: Actor,
        contextId: UUID,
        parentId: UUID? = null,
    ): List<Folder>

    /** The folder and its parents, top first. */
    fun folderPath(
        actor: Actor,
        folderId: UUID,
    ): List<Folder>

    fun createFolder(
        actor: Actor,
        contextId: UUID,
        parentId: UUID?,
        name: String,
    ): Folder

    fun renameFolder(
        actor: Actor,
        folderId: UUID,
        name: String,
    ): Folder

    fun deleteFolder(
        actor: Actor,
        folderId: UUID,
    )

    fun move(
        actor: Actor,
        objectId: MasterId,
        to: MoveObject,
    ): VersionedObject

    /** Why the actor may not delete it, or null when they may. */
    fun whyNotDelete(
        actor: Actor,
        objectId: MasterId,
    ): String?

    /** Removes the object and all its versions. */
    fun delete(
        actor: Actor,
        objectId: MasterId,
    )

    /** The owner, its context's managers or a site administrator hand it to someone else. */
    fun changeOwner(
        actor: Actor,
        objectId: MasterId,
        owner: String,
    ): VersionedObject

    /** Keeps others from checking it out until released. Needs MODIFY. */
    fun reserve(
        actor: Actor,
        objectId: MasterId,
    ): VersionedObject

    /** By whoever reserved it, or a site administrator. */
    fun release(
        actor: Actor,
        objectId: MasterId,
    ): VersionedObject

    fun deleteRules(actor: Actor): DeleteRules

    fun setDeleteRules(
        actor: Actor,
        rules: DeleteRules,
    ): DeleteRules
}

data class FolderChanged(
    val folderId: UUID,
    val contextId: UUID,
    val name: String,
    /** CREATED, RENAMED, DELETED */
    val change: String,
) : DomainEvent {
    override val eventType = "versioning.FolderChanged.v1"
    override val aggregateType = "folder"
    override val aggregateId: UUID = folderId
}

data class ObjectMoved(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    override val iterationId: UUID,
    override val version: String,
    val fromContextId: UUID,
    val fromFolderId: UUID?,
    val toFolderId: UUID?,
) : VersioningEvent {
    override val eventType = "versioning.ObjectMoved.v1"
}

data class ObjectDeleted(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    override val iterationId: UUID,
    override val version: String,
    val name: String,
    val state: String,
) : VersioningEvent {
    override val eventType = "versioning.ObjectDeleted.v1"
}

data class OwnershipChanged(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    override val iterationId: UUID,
    override val version: String,
    /** OWNER_CHANGED, RESERVED, RELEASED */
    val change: String,
    val owner: String?,
    val reservedBy: String?,
) : VersioningEvent {
    override val eventType = "versioning.OwnershipChanged.v1"
}
