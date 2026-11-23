package com.plm.workspace.infra.web

import com.plm.platform.actor.Actor
import com.plm.workspace.api.AddNewFile
import com.plm.workspace.api.AddToWorkspace
import com.plm.workspace.api.CreateFromFile
import com.plm.workspace.api.CreateWorkspace
import com.plm.workspace.api.ReportLocal
import com.plm.workspace.api.UploadChange
import com.plm.workspace.api.WorkspaceOperation
import com.plm.workspace.api.WorkspaceService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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

data class RemoveFromWorkspace(
    val ids: List<UUID>,
)

/** Personal workspaces (roadmap 8.1). Every route works on the caller's own workspaces only. */
@RestController
internal class WorkspaceController(
    private val workspaces: WorkspaceService,
) {
    @GetMapping("/api/v1/workspaces")
    fun list(actor: Actor) = workspaces.list(actor)

    @PostMapping("/api/v1/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody command: CreateWorkspace,
    ) = workspaces.create(actor, command)

    @GetMapping("/api/v1/workspaces/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = workspaces.get(actor, id)

    @PutMapping("/api/v1/workspaces/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: CreateWorkspace,
    ) = workspaces.update(actor, id, command)

    @DeleteMapping("/api/v1/workspaces/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = workspaces.delete(actor, id)

    @PostMapping("/api/v1/workspaces/{id}/items")
    fun add(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: AddToWorkspace,
    ) = workspaces.add(actor, id, command)

    @PostMapping("/api/v1/workspaces/{id}/items/remove")
    fun remove(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: RemoveFromWorkspace,
    ) = workspaces.remove(actor, id, request.ids)

    @PostMapping("/api/v1/workspaces/{id}/local")
    fun reportLocal(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody report: ReportLocal,
    ) = workspaces.reportLocal(actor, id, report)

    @GetMapping("/api/v1/workspaces/{id}/downloads")
    fun downloads(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(required = false) ids: List<UUID>?,
    ) = workspaces.downloads(actor, id, ids.orEmpty())

    @PostMapping("/api/v1/workspaces/{id}/check-out")
    fun checkOut(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody op: WorkspaceOperation,
    ) = workspaces.checkOut(actor, id, op)

    @PostMapping("/api/v1/workspaces/{id}/check-in")
    fun checkIn(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody op: WorkspaceOperation,
    ) = workspaces.checkIn(actor, id, op)

    @PostMapping("/api/v1/workspaces/{id}/undo")
    fun undo(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody op: WorkspaceOperation,
    ) = workspaces.undo(actor, id, op)

    @PostMapping("/api/v1/workspaces/{id}/upload")
    fun upload(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody change: UploadChange,
    ) = workspaces.upload(actor, id, change)

    @PostMapping("/api/v1/workspaces/{id}/new-files")
    @ResponseStatus(HttpStatus.CREATED)
    fun addNewFile(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody file: AddNewFile,
    ) = workspaces.addNewFile(actor, id, file)

    @DeleteMapping("/api/v1/workspaces/{id}/new-files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeNewFile(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable fileId: UUID,
    ) = workspaces.removeNewFile(actor, id, fileId)

    @PostMapping("/api/v1/workspaces/{id}/new-files/{fileId}/create")
    @ResponseStatus(HttpStatus.CREATED)
    fun createFromFile(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable fileId: UUID,
        @RequestBody command: CreateFromFile,
    ) = workspaces.createFromFile(actor, id, fileId, command)

    @GetMapping("/api/v1/workspaces/{id}/history")
    fun history(
        actor: Actor,
        @PathVariable id: UUID,
    ) = workspaces.history(actor, id)

    @GetMapping("/api/v1/workspaces/{id}/zip")
    fun zip(
        actor: Actor,
        @PathVariable id: UUID,
    ): ResponseEntity<ByteArray> {
        val name =
            workspaces
                .get(actor, id)
                .workspace.name
                .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val bytes =
            java.io
                .ByteArrayOutputStream()
                .also { workspaces.zip(actor, id, it) }
                .toByteArray()
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$name.zip\"")
            .body(bytes)
    }
}
