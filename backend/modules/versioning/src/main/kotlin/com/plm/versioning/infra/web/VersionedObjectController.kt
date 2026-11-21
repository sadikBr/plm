package com.plm.versioning.infra.web

import com.plm.access.api.ContextId
import com.plm.lifecycle.api.LifecycleTemplateId
import com.plm.platform.actor.Actor
import com.plm.types.api.TypeId
import com.plm.versioning.api.ChangeLifecycle
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectLifecycle
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.Rename
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Generic versioned-object endpoints. Type-specific APIs (documents, parts) arrive in Phase 1-2
 * and build on the same engine.
 */
@RestController
@RequestMapping("/api/v1/objects")
internal class VersionedObjectController(
    private val versioning: VersioningService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: CreateObjectRequest,
    ) = ObjectResponse(
        versioning.create(
            actor,
            CreateVersionedObject(
                typeId = TypeId(request.typeId),
                contextId = ContextId(request.contextId),
                name = request.name,
                number = request.number,
                attributes = request.attributes ?: emptyMap(),
                folderId = request.folderId,
            ),
        ),
    )

    /**
     * Objects the caller can read, optionally in one context and/or matching `q` (number or name,
     * case-insensitive substring). Page with `after` = the last `id` of the previous page.
     */
    @GetMapping
    fun list(
        actor: Actor,
        @RequestParam(required = false) contextId: UUID?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) after: UUID?,
        @RequestParam(required = false) folderId: UUID?,
        @RequestParam(defaultValue = "false") topLevelOnly: Boolean,
        /** Only these objects, e.g. to show what reference attributes point to (roadmap 4.12). */
        @RequestParam(required = false) ids: List<UUID>?,
    ) = versioning
        .list(
            actor,
            ObjectQuery(
                contextId?.let(::ContextId),
                q,
                limit,
                after?.let(::MasterId),
                folderId,
                topLevelOnly,
                ids = ids?.map(::MasterId),
            ),
        ).map(::ObjectResponse)

    @GetMapping("/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ObjectResponse(versioning.get(actor, MasterId(id)))

    @GetMapping("/{id}/iterations")
    fun history(
        actor: Actor,
        @PathVariable id: UUID,
    ) = versioning.history(actor, MasterId(id)).map(::IterationResponse)

    @GetMapping("/{id}/working-copy")
    fun workingCopy(
        actor: Actor,
        @PathVariable id: UUID,
    ) = IterationResponse(versioning.workingCopy(actor, MasterId(id)))

    @PatchMapping("/{id}/working-copy")
    fun updateWorkingCopy(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: UpdateWorkingCopyRequest,
    ) = IterationResponse(versioning.updateWorkingCopy(actor, MasterId(id), request.attributes))

    @PostMapping("/{id}/checkout")
    fun checkOut(
        actor: Actor,
        @PathVariable id: UUID,
    ) = IterationResponse(versioning.checkOut(actor, MasterId(id)))

    @PostMapping("/{id}/checkin")
    fun checkIn(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody(required = false) request: CheckInRequest?,
    ) = ObjectResponse(versioning.checkIn(actor, MasterId(id), request?.comment))

    @PostMapping("/{id}/undo-checkout")
    fun undoCheckOut(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ObjectResponse(versioning.undoCheckOut(actor, MasterId(id)))

    /** The objects whose reference attributes point to this one (roadmap 4.12). */
    @GetMapping("/{id}/referenced-by")
    fun referencedBy(
        actor: Actor,
        @PathVariable id: UUID,
    ) = versioning.referencedBy(actor, MasterId(id))

    /** Lifecycle, current state, and what the caller may do next (with reasons for what they may not). */
    @GetMapping("/{id}/lifecycle")
    fun lifecycle(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ObjectLifecycleResponse(versioning.lifecycle(actor, MasterId(id)))

    /** Moves the object to another lifecycle state along an allowed transition (needs PROMOTE). */
    @PostMapping("/{id}/state")
    fun setState(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SetStateRequest,
    ) = ObjectResponse(
        versioning.setState(
            actor,
            MasterId(id),
            SetState(
                request.state,
                request.comment,
                request.override ?: false,
            ),
        ),
    )

    /** Moves the object to another lifecycle or a newer version of its own (site admins). */
    @PostMapping("/{id}/lifecycle")
    fun changeLifecycle(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: ChangeLifecycleRequest,
    ) = ObjectResponse(
        versioning.changeLifecycle(
            actor,
            MasterId(id),
            ChangeLifecycle(LifecycleTemplateId(request.lifecycleId), request.state),
        ),
    )

    /** Changes the number and/or name (all versions). Needs MANAGE; not while checked out. */
    @PostMapping("/{id}/rename")
    fun rename(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: RenameRequest,
    ) = ObjectResponse(versioning.rename(actor, MasterId(id), Rename(request.number, request.name, request.comment)))

    /** Starts a one-off version for one order or unit (roadmap 7.10), checked out to you. */
    @PostMapping("/{id}/one-offs")
    fun makeOneOff(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: MakeOneOff,
    ) = IterationResponse(versioning.makeOneOff(actor, MasterId(id), request.baseIterationId, request.forWhat))

    @PostMapping("/{id}/revise")
    fun revise(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ObjectResponse(versioning.revise(actor, MasterId(id)))
}

internal data class CreateObjectRequest(
    val typeId: UUID,
    val contextId: UUID,
    val name: String,
    val number: String? = null,
    val attributes: Map<String, Any?>? = null,
    val folderId: UUID? = null,
)

internal data class UpdateWorkingCopyRequest(
    val attributes: Map<String, Any?>,
)

internal data class CheckInRequest(
    val comment: String? = null,
)

internal data class ObjectResponse(
    val id: UUID,
    val number: String,
    val name: String,
    val typeId: UUID,
    val contextId: UUID,
    val version: String,
    val checkedOutBy: String?,
    val latest: IterationResponse,
    val createdAt: Instant,
    val createdBy: String,
    val folderId: UUID?,
    val owner: String,
    val reservedBy: String?,
) {
    constructor(o: VersionedObject) : this(
        id = o.id.value,
        number = o.number,
        name = o.name,
        typeId = o.typeId.value,
        contextId = o.contextId.value,
        version = o.latest.version,
        checkedOutBy = o.checkedOutBy,
        latest = IterationResponse(o.latest),
        createdAt = o.createdAt,
        createdBy = o.createdBy,
        folderId = o.folderId,
        owner = o.owner,
        reservedBy = o.reservedBy,
    )
}

internal data class IterationResponse(
    val id: UUID,
    val version: String,
    val revision: String,
    val iteration: Int,
    val status: String,
    val state: String,
    val attributes: Map<String, Any?>,
    val basedOnId: UUID?,
    val comment: String?,
    val createdAt: Instant,
    val createdBy: String,
    val checkedInAt: Instant?,
    val checkedOutBy: String?,
    /** A one-off's number on its branch (roadmap 7.10), and what it was made for. */
    val oneOff: Int? = null,
    val oneOffFor: String? = null,
) {
    constructor(i: Iteration) : this(
        id = i.id.value,
        version = i.version,
        revision = i.revision,
        iteration = i.iteration,
        status = i.status.name,
        state = i.state,
        attributes = i.attributes,
        basedOnId = i.basedOnId?.value,
        comment = i.comment,
        createdAt = i.createdAt,
        createdBy = i.createdBy,
        checkedInAt = i.checkedInAt,
        checkedOutBy = i.checkedOutBy,
        oneOff = i.oneOff,
        oneOffFor = i.oneOffFor,
    )
}

internal data class MakeOneOff(
    /** What it's for: an order, a unit. */
    val forWhat: String,
    /** The version to start from; the latest by default. */
    val baseIterationId: UUID? = null,
)

internal data class SetStateRequest(
    val state: String,
    val comment: String? = null,
    /** Site administrators only: ignore the lifecycle's transitions. */
    val override: Boolean? = null,
)

internal data class LifecycleStateResponse(
    val key: String,
    val labels: Map<String, String>,
    val editable: Boolean,
    val revisable: Boolean,
)

internal data class ObjectLifecycleResponse(
    val lifecycleId: UUID,
    val lifecycleName: String,
    /** The lifecycle version this object follows. */
    val lifecycleVersion: Int,
    /** False when a newer version of the lifecycle exists (an admin can move the object to it). */
    val lifecycleLatest: Boolean,
    val states: List<LifecycleStateResponse>,
    val state: String,
    val editable: Boolean,
    val revisable: Boolean,
    val revisionScheme: String,
    /** States the caller may move the object to now. */
    val nextStates: List<String>,
    val canOverride: Boolean,
) {
    constructor(l: ObjectLifecycle) : this(
        l.template.id.value,
        l.template.name,
        l.template.version,
        l.template.latest,
        l.template.states.map { LifecycleStateResponse(it.key, it.labels, it.editable, it.revisable) },
        l.state,
        l.editable,
        l.revisable,
        l.revisionScheme.name,
        l.nextStates,
        l.canOverride,
    )
}

internal data class RenameRequest(
    /** New number; null keeps the current one. */
    val number: String? = null,
    /** New name; null keeps the current one. */
    val name: String? = null,
    val comment: String? = null,
)

internal data class ChangeLifecycleRequest(
    val lifecycleId: UUID,
    /** The state in the new lifecycle; omit to keep the current one. */
    val state: String? = null,
)
