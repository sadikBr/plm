package com.plm.versioning.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.Permission
import com.plm.identity.api.PrincipalDirectory
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.DeleteRules
import com.plm.versioning.api.Deleter
import com.plm.versioning.api.Folder
import com.plm.versioning.api.FolderChanged
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.MoveObject
import com.plm.versioning.api.ObjectDeleted
import com.plm.versioning.api.ObjectMoved
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.OwnershipChanged
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import com.plm.versioning.infra.OrganizingRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultOrganizingService(
    private val repository: OrganizingRepository,
    private val versioning: VersioningService,
    private val contexts: ContextService,
    private val access: AccessService,
    private val directory: PrincipalDirectory,
    private val events: DomainEvents,
    private val guards: ObjectProvider<OperationGuard>,
    private val clock: Clock,
) : OrganizingService {
    // ---------- Folders ----------

    @Transactional(readOnly = true)
    override fun folders(
        actor: Actor,
        contextId: UUID,
        parentId: UUID?,
    ): List<Folder> {
        contexts.get(actor, ContextId(contextId))
        if (parentId != null) folder(actor, parentId, contextId)
        return repository.folders(actor.tenant, contextId, parentId)
    }

    @Transactional(readOnly = true)
    override fun folderPath(
        actor: Actor,
        folderId: UUID,
    ): List<Folder> {
        val path = ArrayDeque<Folder>()
        var current: Folder? = folder(actor, folderId)
        while (current != null && path.size < 100) {
            path.addFirst(current)
            current = current.parentId?.let { repository.folder(actor.tenant, it) }
        }
        return path.toList()
    }

    override fun createFolder(
        actor: Actor,
        contextId: UUID,
        parentId: UUID?,
        name: String,
    ): Folder {
        contexts.get(actor, ContextId(contextId))
        access.require(actor, AccessTarget(ContextId(contextId)), Permission.CREATE)
        if (parentId != null) folder(actor, parentId, contextId)
        val n = folderName(name)
        val id = UuidV7.generate(clock)
        if (!repository.insertFolder(
                actor.tenant,
                id,
                contextId,
                parentId,
                n,
                actor.principal.value,
                clock.instant(),
            )
        ) {
            throw ConflictException("DUPLICATE_FOLDER", "There's already a folder named $n here")
        }
        events.publish(actor, FolderChanged(id, contextId, n, "CREATED"))
        return repository.folder(actor.tenant, id)!!
    }

    override fun renameFolder(
        actor: Actor,
        folderId: UUID,
        name: String,
    ): Folder {
        val f = folder(actor, folderId)
        access.require(actor, AccessTarget(ContextId(f.contextId)), Permission.MANAGE)
        val n = folderName(name)
        if (!repository.renameFolder(
                folderId,
                n,
            )
        ) {
            throw ConflictException("DUPLICATE_FOLDER", "There's already a folder named $n here")
        }
        events.publish(actor, FolderChanged(folderId, f.contextId, n, "RENAMED"))
        return repository.folder(actor.tenant, folderId)!!
    }

    override fun deleteFolder(
        actor: Actor,
        folderId: UUID,
    ) {
        val f = folder(actor, folderId)
        access.require(actor, AccessTarget(ContextId(f.contextId)), Permission.MANAGE)
        if (f.folders > 0 || f.objects > 0) {
            throw ConflictException("FOLDER_NOT_EMPTY", "${f.name} isn't empty: move or delete what's in it first")
        }
        repository.deleteFolder(folderId)
        events.publish(actor, FolderChanged(folderId, f.contextId, f.name, "DELETED"))
    }

    /** A folder the actor can see (its context is visible), optionally checked to be in [contextId]. */
    private fun folder(
        actor: Actor,
        id: UUID,
        contextId: UUID? = null,
    ): Folder {
        val f = repository.folder(actor.tenant, id)
        val missing = NotFoundException("FOLDER_NOT_FOUND", "There's no such folder", mapOf("folderId" to id))
        if (f == null || (contextId != null && f.contextId != contextId)) throw missing
        try {
            contexts.get(actor, ContextId(f.contextId))
        } catch (e: NotFoundException) {
            throw missing
        }
        return f
    }

    private fun folderName(name: String): String {
        val n = name.trim()
        if (n.isEmpty() || n.length > 120 || n.contains('/')) {
            throw ValidationException("INVALID_FOLDER", "A folder needs a name (at most 120 characters, no slash)")
        }
        return n
    }

    // ---------- Objects ----------

    override fun move(
        actor: Actor,
        objectId: MasterId,
        to: MoveObject,
    ): VersionedObject {
        val obj = versioning.get(actor, objectId)
        if (obj.checkedOutBy !=
            null
        ) {
            throw ConflictException("CHECKED_OUT", "${obj.number} is checked out: check it in first")
        }
        access.require(
            actor,
            AccessTarget(obj.contextId, obj.typeId, obj.latest.state, obj.id.value),
            Permission.MODIFY,
        )
        val target = contexts.get(actor, ContextId(to.contextId))
        if (to.folderId != null) folder(actor, to.folderId, to.contextId)
        if (target.id !=
            obj.contextId
        ) {
            access.require(actor, AccessTarget(target.id, obj.typeId, obj.latest.state), Permission.CREATE)
        }
        if (target.id == obj.contextId && to.folderId == obj.folderId) return obj
        guard(
            actor,
            GuardedOperation.MOVE,
            objectId,
            mapOf(
                "toContext" to target.name,
                "toFolder" to to.folderId?.toString(),
            ),
        )
        repository.move(objectId.value, to.contextId, to.folderId)
        events.publish(
            actor,
            ObjectMoved(
                obj.id.value,
                obj.number,
                obj.typeId.value,
                to.contextId,
                obj.latest.id.value,
                obj.latest.version,
                obj.contextId.value,
                obj.folderId,
                to.folderId,
            ),
        )
        return versioning.get(actor, objectId)
    }

    @Transactional(readOnly = true)
    override fun whyNotDelete(
        actor: Actor,
        objectId: MasterId,
    ): String? {
        val obj = versioning.get(actor, objectId)
        if (obj.checkedOutBy != null) return "${obj.number} is checked out"
        if (actor.siteAdmin) return null
        val rules = deleteRules(actor)
        if (rules.states.isNotEmpty() && obj.latest.state !in rules.states) {
            return "Objects can't be deleted in state ${obj.latest.state}"
        }
        val manager = Permission.MANAGE in access.permissions(actor, AccessTarget(obj.contextId))
        val owner = obj.owner == actor.principal.value
        val may = (Deleter.OWNER in rules.who && owner) || (Deleter.MANAGERS in rules.who && manager)
        return if (may) {
            null
        } else {
            "Only ${rules.who.joinToString(
                " or ",
            ) { if (it == Deleter.OWNER) "its owner" else "its context's managers" }} can delete it"
        }
    }

    override fun delete(
        actor: Actor,
        objectId: MasterId,
    ) {
        val obj = versioning.get(actor, objectId)
        whyNotDelete(actor, objectId)?.let { throw ConflictException("DELETE_NOT_ALLOWED", it) }
        guard(actor, GuardedOperation.DELETE, objectId)
        repository.deleteObject(objectId.value)
        events.publish(
            actor,
            ObjectDeleted(
                obj.id.value,
                obj.number,
                obj.typeId.value,
                obj.contextId.value,
                obj.latest.id.value,
                obj.latest.version,
                obj.name,
                obj.latest.state,
            ),
        )
    }

    override fun changeOwner(
        actor: Actor,
        objectId: MasterId,
        owner: String,
    ): VersionedObject {
        val obj = versioning.get(actor, objectId)
        val manager = Permission.MANAGE in access.permissions(actor, AccessTarget(obj.contextId))
        if (!actor.siteAdmin && !manager && obj.owner != actor.principal.value) {
            throw ForbiddenException(
                "ACCESS_DENIED",
                "Only its owner, its context's managers or an administrator can hand it over",
            )
        }
        val principal =
            directory.findByUsername(actor, owner) ?: directory.get(actor, PrincipalId(owner))
        repository.setOwner(objectId.value, principal.id.value)
        events.publish(actor, ownership(obj, "OWNER_CHANGED", principal.id.value, obj.reservedBy))
        return versioning.get(actor, objectId)
    }

    override fun reserve(
        actor: Actor,
        objectId: MasterId,
    ): VersionedObject {
        val obj = versioning.get(actor, objectId)
        access.require(
            actor,
            AccessTarget(obj.contextId, obj.typeId, obj.latest.state, obj.id.value),
            Permission.MODIFY,
        )
        if (obj.reservedBy != null && obj.reservedBy != actor.principal.value) {
            throw ConflictException(
                "RESERVED_BY_ANOTHER_USER",
                "${obj.number} is already reserved by ${directory.displayName(
                    actor.tenant,
                    PrincipalId(obj.reservedBy),
                )}",
            )
        }
        if (obj.checkedOutBy != null && obj.checkedOutBy != actor.principal.value) {
            throw ConflictException(
                "CHECKED_OUT",
                "${obj.number} is checked out by ${directory.displayName(actor.tenant, PrincipalId(obj.checkedOutBy))}",
            )
        }
        repository.setReservation(objectId.value, actor.principal.value, clock.instant())
        events.publish(actor, ownership(obj, "RESERVED", obj.owner, actor.principal.value))
        return versioning.get(actor, objectId)
    }

    override fun release(
        actor: Actor,
        objectId: MasterId,
    ): VersionedObject {
        val obj = versioning.get(actor, objectId)
        val by = obj.reservedBy ?: throw ConflictException("NOT_RESERVED", "${obj.number} isn't reserved")
        if (by != actor.principal.value && !actor.siteAdmin) {
            throw ForbiddenException(
                "ACCESS_DENIED",
                "Only ${directory.displayName(actor.tenant, PrincipalId(by))} or an administrator can release it",
            )
        }
        repository.setReservation(objectId.value, null, null)
        events.publish(actor, ownership(obj, "RELEASED", obj.owner, null))
        return versioning.get(actor, objectId)
    }

    private fun ownership(
        obj: VersionedObject,
        change: String,
        owner: String?,
        reservedBy: String?,
    ) = OwnershipChanged(
        obj.id.value,
        obj.number,
        obj.typeId.value,
        obj.contextId.value,
        obj.latest.id.value,
        obj.latest.version,
        change,
        owner,
        reservedBy,
    )

    // ---------- Delete rules ----------

    @Transactional(readOnly = true)
    override fun deleteRules(actor: Actor): DeleteRules = repository.deleteRules(actor.tenant) ?: DeleteRules()

    override fun setDeleteRules(
        actor: Actor,
        rules: DeleteRules,
    ): DeleteRules {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators set the delete rules",
            )
        }
        repository.saveDeleteRules(
            actor.tenant,
            rules.copy(
                states =
                    rules.states
                        .map {
                            it.trim()
                        }.filter { it.isNotEmpty() }
                        .distinct(),
            ),
            actor.principal.value,
            clock.instant(),
        )
        return deleteRules(actor)
    }

    /** Extension rules (roadmap 2.5) can refuse moves and deletes too. */
    private fun guard(
        actor: Actor,
        operation: GuardedOperation,
        objectId: MasterId,
        event: Map<String, Any?> = emptyMap(),
    ) {
        val all = guards.orderedStream().toList()
        if (all.isEmpty()) return
        val obj = versioning.get(actor, objectId)
        val check =
            OperationCheck(operation, obj.typeId, mapOf("operation" to operation.key) + event) {
                versioning.expressionModel(actor, objectId)
            }
        val refusals = all.flatMap { it.check(actor, check) }
        if (refusals.isEmpty()) return
        throw ValidationException(
            "OPERATION_REFUSED",
            refusals.joinToString(" · ") { it.message },
            mapOf(
                "operation" to operation.key,
                "refusals" to refusals.map { mapOf("rule" to it.rule, "source" to it.source, "message" to it.message) },
            ),
        )
    }
}
