package com.plm.lifecycle.infra.web

import com.plm.lifecycle.api.CreateLifecycleTemplate
import com.plm.lifecycle.api.LifecycleService
import com.plm.lifecycle.api.LifecycleState
import com.plm.lifecycle.api.LifecycleTemplate
import com.plm.lifecycle.api.LifecycleTemplateId
import com.plm.lifecycle.api.LifecycleTransition
import com.plm.platform.actor.Actor
import com.plm.types.api.RevisionScheme
import com.plm.types.api.TypeId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class LifecycleController(
    private val lifecycles: LifecycleService,
) {
    @GetMapping("/api/v1/lifecycles")
    fun list(actor: Actor) = lifecycles.list(actor).map(::LifecycleResponse)

    @GetMapping("/api/v1/lifecycles/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = LifecycleResponse(lifecycles.get(actor, LifecycleTemplateId(id)))

    @PostMapping("/api/v1/lifecycles")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: CreateLifecycleRequest,
    ) = LifecycleResponse(lifecycles.create(actor, request.command()))

    /**
     * Saves a new version of this lifecycle (it must be the latest version). Types using it move to
     * the new version; existing objects keep theirs. Site admins only.
     */
    @PutMapping("/api/v1/lifecycles/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: CreateLifecycleRequest,
    ) = LifecycleResponse(lifecycles.update(actor, LifecycleTemplateId(id), request.command()))

    /** Every version of this lifecycle, newest first. */
    @GetMapping("/api/v1/lifecycles/{id}/versions")
    fun versions(
        actor: Actor,
        @PathVariable id: UUID,
    ) = lifecycles.versions(actor, LifecycleTemplateId(id)).map(::LifecycleResponse)

    /** The lifecycle objects of this type get (inherited from the nearest ancestor if not set). */
    @GetMapping("/api/v1/types/{typeId}/lifecycle")
    fun forType(
        actor: Actor,
        @PathVariable typeId: UUID,
    ) = LifecycleResponse(lifecycles.forType(actor, TypeId(typeId)))

    /** Applies to objects created from now on; existing objects keep the lifecycle they were created with. */
    @PutMapping("/api/v1/types/{typeId}/lifecycle")
    fun assign(
        actor: Actor,
        @PathVariable typeId: UUID,
        @RequestBody request: AssignLifecycleRequest,
    ) = LifecycleResponse(lifecycles.assign(actor, TypeId(typeId), LifecycleTemplateId(request.lifecycleId)))
}

internal data class StateDto(
    val key: String,
    val labels: Map<String, String>,
    val editable: Boolean? = null,
    val revisable: Boolean? = null,
    /** Entry criteria: attributes that must have a value to enter this state. */
    val requiredAttributes: List<String>? = null,
    /** Revisions started from this state continue in this series. */
    val revisionScheme: RevisionScheme? = null,
    /** Gate: a CEL condition that must hold to enter this state, and the message when it doesn't. */
    val entryCondition: String? = null,
    val entryMessage: String? = null,
)

internal data class TransitionDto(
    val from: String,
    val to: String,
)

internal data class CreateLifecycleRequest(
    val name: String,
    val labels: Map<String, String>,
    val initialState: String,
    val states: List<StateDto>,
    val transitions: List<TransitionDto>,
) {
    fun command() =
        CreateLifecycleTemplate(
            name,
            labels,
            initialState,
            states.map {
                LifecycleState(
                    it.key,
                    it.labels,
                    it.editable ?: false,
                    it.revisable ?: false,
                    it.requiredAttributes.orEmpty(),
                    it.revisionScheme,
                    it.entryCondition?.trim()?.ifEmpty { null },
                    it.entryMessage?.trim()?.ifEmpty { null },
                )
            },
            transitions.map { LifecycleTransition(it.from, it.to) },
        )
}

internal data class AssignLifecycleRequest(
    val lifecycleId: UUID,
)

internal data class LifecycleResponse(
    val id: UUID,
    val name: String,
    val labels: Map<String, String>,
    val initialState: String,
    val states: List<StateDto>,
    val transitions: List<TransitionDto>,
    val system: Boolean,
    val version: Int,
    /** Whether this is the current version, the one new objects get. */
    val latest: Boolean,
) {
    constructor(t: LifecycleTemplate) : this(
        t.id.value,
        t.name,
        t.labels,
        t.initialState,
        t.states.map {
            StateDto(
                it.key,
                it.labels,
                it.editable,
                it.revisable,
                it.requiredAttributes,
                it.revisionScheme,
                it.entryCondition,
                it.entryMessage,
            )
        },
        t.transitions.map { TransitionDto(it.from, it.to) },
        t.system,
        t.version,
        t.latest,
    )
}
