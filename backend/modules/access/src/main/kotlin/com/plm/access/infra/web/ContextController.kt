package com.plm.access.infra.web

import com.plm.access.api.Context
import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.ContextTemplateId
import com.plm.access.api.ContextTemplateService
import com.plm.access.api.CreateContext
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/contexts")
internal class ContextController(
    private val contexts: ContextService,
    private val templates: ContextTemplateService,
) {
    /** The tenant's root context. */
    @GetMapping("/site")
    fun site(actor: Actor) = ContextResponse(contexts.get(actor, ContextId.DEFAULT_SITE))

    @GetMapping("/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ContextResponse(contexts.get(actor, ContextId(id)))

    @GetMapping("/{id}/children")
    fun children(
        actor: Actor,
        @PathVariable id: UUID,
    ) = contexts.children(actor, ContextId(id)).map(::ContextResponse)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: CreateContextRequest,
    ): ContextResponse {
        val command = CreateContext(ContextId(request.parentId), request.kind, request.name)
        return ContextResponse(
            request.templateId?.let { templates.createContext(actor, command, ContextTemplateId(it)) }
                ?: contexts.create(actor, command),
        )
    }
}

internal data class CreateContextRequest(
    val parentId: UUID,
    val kind: ContextKind,
    val name: String,
    /** Start from this context template (its kind must match). */
    val templateId: UUID? = null,
)

internal data class ContextResponse(
    val id: UUID,
    val parentId: UUID?,
    val kind: ContextKind,
    val name: String,
    val createdAt: Instant,
    val createdBy: String,
) {
    constructor(c: Context) : this(c.id.value, c.parentId?.value, c.kind, c.name, c.createdAt, c.createdBy)
}
