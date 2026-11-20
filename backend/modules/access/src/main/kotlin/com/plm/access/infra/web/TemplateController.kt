package com.plm.access.infra.web

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextTemplate
import com.plm.access.api.ContextTemplateId
import com.plm.access.api.ContextTemplateService
import com.plm.access.api.SaveContextTemplate
import com.plm.access.api.SharedTeam
import com.plm.access.api.SharedTeamId
import com.plm.access.api.SharedTeamService
import com.plm.access.api.TeamRole
import com.plm.access.api.TemplateContent
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
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
import java.time.Instant
import java.util.UUID

@RestController
internal class ContextTemplateController(
    private val templates: ContextTemplateService,
) {
    /**
     * Templates the caller can see. With `kind` and `parentId`: those usable for a new context of
     * that kind under that parent (site-wide ones, and the parent's organization's own).
     */
    @GetMapping("/api/v1/context-templates")
    fun list(
        actor: Actor,
        @RequestParam(required = false) kind: ContextKind?,
        @RequestParam(required = false) parentId: UUID?,
    ) = templates.list(actor, kind, parentId?.let(::ContextId)).map(::ContextTemplateResponse)

    @GetMapping("/api/v1/context-templates/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ContextTemplateResponse(templates.get(actor, ContextTemplateId(id)))

    @PostMapping("/api/v1/context-templates")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: SaveContextTemplateRequest,
    ) = ContextTemplateResponse(templates.create(actor, request.command()))

    @PutMapping("/api/v1/context-templates/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveContextTemplateRequest,
    ) = ContextTemplateResponse(templates.update(actor, ContextTemplateId(id), request.command()))

    @DeleteMapping("/api/v1/context-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = templates.delete(actor, ContextTemplateId(id))

    /** Saves this context's own access rules, team and shared teams as a new template. Site admins only. */
    @PostMapping("/api/v1/contexts/{contextId}/save-as-template")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveFromContext(
        actor: Actor,
        @PathVariable contextId: UUID,
        @RequestBody request: SaveFromContextRequest,
    ) = ContextTemplateResponse(
        templates.saveFromContext(
            actor,
            ContextId(contextId),
            request.name,
            request.description,
            request.siteWide ?: false,
        ),
    )
}

@RestController
internal class SharedTeamController(
    private val teams: SharedTeamService,
) {
    /** Shared teams the caller can see: site-wide ones and their organizations' own. */
    @GetMapping("/api/v1/shared-teams")
    fun list(actor: Actor) = teams.list(actor).map(::SharedTeamResponse)

    /** Shared teams that can be attached to this context: site-wide ones and its organization's. */
    @GetMapping("/api/v1/contexts/{contextId}/shared-teams/usable")
    fun usable(
        actor: Actor,
        @PathVariable contextId: UUID,
    ) = teams.usableIn(actor, ContextId(contextId)).map(::SharedTeamResponse)

    @GetMapping("/api/v1/shared-teams/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = SharedTeamResponse(teams.get(actor, SharedTeamId(id)))

    @PostMapping("/api/v1/shared-teams")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: CreateSharedTeamRequest,
    ) = SharedTeamResponse(
        teams.create(actor, request.name, request.description, request.organizationId?.let(::ContextId)),
    )

    @DeleteMapping("/api/v1/shared-teams/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = teams.delete(actor, SharedTeamId(id))

    @PutMapping("/api/v1/shared-teams/{id}/members/{principalId}")
    fun putMember(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable principalId: String,
        @RequestBody request: PutMemberRequest,
    ) = SharedTeamResponse(teams.putMember(actor, SharedTeamId(id), PrincipalId(principalId), TeamRole(request.role)))

    @DeleteMapping("/api/v1/shared-teams/{id}/members/{principalId}")
    fun removeMember(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable principalId: String,
    ) = SharedTeamResponse(teams.removeMember(actor, SharedTeamId(id), PrincipalId(principalId)))

    /** Shared teams attached to this context. */
    @GetMapping("/api/v1/contexts/{contextId}/shared-teams")
    fun attached(
        actor: Actor,
        @PathVariable contextId: UUID,
    ) = teams.attached(actor, ContextId(contextId)).map(::SharedTeamResponse)

    /** Attaches a shared team: its members get their roles here. Needs MANAGE. */
    @PutMapping("/api/v1/contexts/{contextId}/shared-teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun attach(
        actor: Actor,
        @PathVariable contextId: UUID,
        @PathVariable teamId: UUID,
    ) = teams.attach(actor, ContextId(contextId), SharedTeamId(teamId))

    @DeleteMapping("/api/v1/contexts/{contextId}/shared-teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun detach(
        actor: Actor,
        @PathVariable contextId: UUID,
        @PathVariable teamId: UUID,
    ) = teams.detach(actor, ContextId(contextId), SharedTeamId(teamId))
}

internal data class SaveContextTemplateRequest(
    val name: String,
    val kind: ContextKind,
    val description: String? = null,
    val content: TemplateContent? = null,
    /** The organization this template belongs to (its managers own it); null: site-wide. Fixed at creation. */
    val organizationId: UUID? = null,
) {
    fun command() =
        SaveContextTemplate(name, kind, description, content ?: TemplateContent(), organizationId?.let(::ContextId))
}

internal data class SaveFromContextRequest(
    val name: String,
    val description: String? = null,
    /** Site admins: make it site-wide instead of the context's organization's own. */
    val siteWide: Boolean? = null,
)

internal data class ContextTemplateResponse(
    val id: UUID,
    val name: String,
    val kind: ContextKind,
    val description: String?,
    val content: TemplateContent,
    val organizationId: UUID?,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant,
) {
    constructor(t: ContextTemplate) : this(
        t.id.value,
        t.name,
        t.kind,
        t.description,
        t.content,
        t.organizationId?.value,
        t.createdAt,
        t.createdBy,
        t.updatedAt,
    )
}

internal data class CreateSharedTeamRequest(
    val name: String,
    val description: String? = null,
    /** The organization the team belongs to (its managers own it); null: site-wide. */
    val organizationId: UUID? = null,
)

internal data class SharedTeamMemberResponse(
    val principalId: String,
    val role: String,
    val addedAt: Instant,
)

internal data class SharedTeamResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val organizationId: UUID?,
    val members: List<SharedTeamMemberResponse>,
    val createdAt: Instant,
) {
    constructor(t: SharedTeam) : this(
        t.id.value,
        t.name,
        t.description,
        t.organizationId?.value,
        t.members.map { SharedTeamMemberResponse(it.principalId.value, it.role.key, it.addedAt) },
        t.createdAt,
    )
}
