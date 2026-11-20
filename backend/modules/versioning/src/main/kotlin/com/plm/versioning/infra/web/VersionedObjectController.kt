package com.plm.versioning.infra.web

import com.plm.access.api.ContextId
import com.plm.platform.actor.Actor
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
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
    ) = versioning
        .list(actor, ObjectQuery(contextId?.let(::ContextId), q, limit, after?.let(::MasterId)))
        .map(::ObjectResponse)

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
    )
}
