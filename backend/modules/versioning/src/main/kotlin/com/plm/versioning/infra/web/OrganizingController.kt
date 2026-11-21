package com.plm.versioning.infra.web

import com.plm.platform.actor.Actor
import com.plm.versioning.api.DeleteRules
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.MoveObject
import com.plm.versioning.api.OrganizingService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class OrganizingController(
    private val organizing: OrganizingService,
) {
    /** Sub-folders of `parentId`, or the context's top folders. */
    @GetMapping("/api/v1/contexts/{contextId}/folders")
    fun folders(
        actor: Actor,
        @PathVariable contextId: UUID,
        @RequestParam(required = false) parentId: UUID?,
    ) = organizing.folders(actor, contextId, parentId)

    @PostMapping("/api/v1/contexts/{contextId}/folders")
    @ResponseStatus(HttpStatus.CREATED)
    fun createFolder(
        actor: Actor,
        @PathVariable contextId: UUID,
        @RequestBody request: FolderRequest,
    ) = organizing.createFolder(actor, contextId, request.parentId, request.name)

    /** The folder and its parents, top first. */
    @GetMapping("/api/v1/folders/{id}/path")
    fun path(
        actor: Actor,
        @PathVariable id: UUID,
    ) = organizing.folderPath(actor, id)

    @PutMapping("/api/v1/folders/{id}")
    fun rename(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: FolderRequest,
    ) = organizing.renameFolder(actor, id, request.name)

    @DeleteMapping("/api/v1/folders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteFolder(
        actor: Actor,
        @PathVariable id: UUID,
    ) = organizing.deleteFolder(actor, id)

    @PostMapping("/api/v1/objects/{id}/move")
    fun move(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: MoveObject,
    ) = ObjectResponse(organizing.move(actor, MasterId(id), request))

    /** Whether you may delete it, and why not. */
    @GetMapping("/api/v1/objects/{id}/deletable")
    fun deletable(
        actor: Actor,
        @PathVariable id: UUID,
    ) = organizing.whyNotDelete(actor, MasterId(id)).let { Deletable(it == null, it) }

    @DeleteMapping("/api/v1/objects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = organizing.delete(actor, MasterId(id))

    @PostMapping("/api/v1/objects/{id}/owner")
    fun changeOwner(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: OwnerRequest,
    ) = ObjectResponse(organizing.changeOwner(actor, MasterId(id), request.owner))

    @PostMapping("/api/v1/objects/{id}/reserve")
    fun reserve(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ObjectResponse(organizing.reserve(actor, MasterId(id)))

    @PostMapping("/api/v1/objects/{id}/release")
    fun release(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ObjectResponse(organizing.release(actor, MasterId(id)))

    @GetMapping("/api/v1/delete-rules")
    fun deleteRules(actor: Actor) = organizing.deleteRules(actor)

    @PutMapping("/api/v1/delete-rules")
    fun setDeleteRules(
        actor: Actor,
        @RequestBody rules: DeleteRules,
    ) = organizing.setDeleteRules(actor, rules)
}

internal data class FolderRequest(
    val name: String,
    val parentId: UUID? = null,
)

internal data class OwnerRequest(
    /** A user id or username. */
    val owner: String,
)

internal data class Deletable(
    val allowed: Boolean,
    val reason: String?,
)
