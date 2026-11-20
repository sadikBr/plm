package com.plm.access.infra.web

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.TeamMember
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.types.api.TypeId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/contexts/{contextId}")
internal class TeamController(
    private val teams: TeamService,
    private val access: AccessService,
    private val contexts: ContextService,
) {
    @GetMapping("/members")
    fun members(
        actor: Actor,
        @PathVariable contextId: UUID,
    ) = teams.members(actor, ContextId(contextId)).map(::MemberResponse)

    /** Everyone with a role here, including members of parent contexts (whose roles are inherited), nearest first. */
    @GetMapping("/members/effective")
    fun effectiveMembers(
        actor: Actor,
        @PathVariable contextId: UUID,
    ) = teams.effectiveMembers(actor, ContextId(contextId)).map(::MemberResponse)

    /** Adds a member or changes their role. */
    @PutMapping("/members/{principalId}")
    fun put(
        actor: Actor,
        @PathVariable contextId: UUID,
        @PathVariable principalId: String,
        @RequestBody request: PutMemberRequest,
    ) = MemberResponse(teams.put(actor, ContextId(contextId), PrincipalId(principalId), TeamRole(request.role)))

    @DeleteMapping("/members/{principalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        actor: Actor,
        @PathVariable contextId: UUID,
        @PathVariable principalId: String,
    ) = teams.remove(actor, ContextId(contextId), PrincipalId(principalId))

    /** What the caller may do here, optionally for one type. Lets the UI enable or disable actions. */
    @GetMapping("/permissions")
    fun permissions(
        actor: Actor,
        @PathVariable contextId: UUID,
        @RequestParam(required = false) typeId: UUID?,
    ): List<String> {
        val context = contexts.get(actor, ContextId(contextId)) // 404 unless visible
        return access.permissions(actor, AccessTarget(context.id, typeId?.let(::TypeId))).map { it.name }.sorted()
    }
}

internal data class PutMemberRequest(
    /** A team role key, e.g. `MEMBER` (see `GET /api/v1/team-roles`). */
    val role: String,
)

internal data class MemberResponse(
    val contextId: UUID,
    val principalId: String,
    val role: String,
    val addedAt: Instant,
    val addedBy: String,
    /** Set when the role comes from an attached shared team. */
    val sharedTeamId: UUID? = null,
) {
    constructor(
        m: TeamMember,
    ) : this(m.contextId.value, m.principalId.value, m.role.key, m.addedAt, m.addedBy, m.sharedTeamId?.value)
}
