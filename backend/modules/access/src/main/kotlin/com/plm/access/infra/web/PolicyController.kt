package com.plm.access.infra.web

import com.plm.access.api.AccessExplanation
import com.plm.access.api.AccessPolicy
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.CreatePolicy
import com.plm.access.api.Permission
import com.plm.access.api.PolicyEffect
import com.plm.access.api.PolicyId
import com.plm.access.api.PolicyService
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamRoleDefinition
import com.plm.access.api.TeamRoleService
import com.plm.access.api.TeamRoleSettings
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.types.api.TypeId
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
internal class PolicyController(
    private val policies: PolicyService,
) {
    /** Policies in effect here: this context's own and its parents' (`inherited: true`), root first. Needs MANAGE. */
    @GetMapping("/api/v1/contexts/{contextId}/policies")
    fun list(
        actor: Actor,
        @PathVariable contextId: UUID,
    ) = policies.policies(actor, ContextId(contextId)).map { PolicyResponse(it, ContextId(contextId)) }

    /** Adds a policy to this context. Needs MANAGE (site context: site admins only). */
    @PostMapping("/api/v1/contexts/{contextId}/policies")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @PathVariable contextId: UUID,
        @RequestBody request: CreatePolicyRequest,
    ) = PolicyResponse(
        policies.create(
            actor,
            ContextId(contextId),
            CreatePolicy(
                role = TeamRole(request.role),
                typeId = request.typeId?.let(::TypeId),
                state = request.state,
                effect = request.effect ?: PolicyEffect.ALLOW,
                permissions = request.permissions.toSet(),
            ),
        ),
        ContextId(contextId),
    )

    /** Replaces a policy's permissions. */
    @PutMapping("/api/v1/policies/{policyId}")
    fun update(
        actor: Actor,
        @PathVariable policyId: UUID,
        @RequestBody request: UpdatePolicyRequest,
    ) = policies.update(actor, PolicyId(policyId), request.permissions.toSet()).let { PolicyResponse(it, it.contextId) }

    @DeleteMapping("/api/v1/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable policyId: UUID,
    ) = policies.delete(actor, PolicyId(policyId))

    /**
     * Why a person can or can't do something here: their roles, and per permission the policies
     * that grant or deny it. About yourself, always; about others, needs MANAGE.
     */
    @GetMapping("/api/v1/contexts/{contextId}/access/explain")
    fun explain(
        actor: Actor,
        @PathVariable contextId: UUID,
        @RequestParam principalId: String,
        @RequestParam(required = false) typeId: UUID?,
        @RequestParam(required = false) state: String?,
    ) = ExplanationResponse(
        policies.explain(
            actor,
            PrincipalId(principalId),
            AccessTarget(ContextId(contextId), typeId?.let(::TypeId), state?.ifBlank { null }),
        ),
    )
}

@RestController
internal class TeamRoleController(
    private val roles: TeamRoleService,
) {
    @GetMapping("/api/v1/team-roles")
    fun list(actor: Actor) = roles.list(actor).map(::TeamRoleResponse)

    /** Defines a new team role. Site admins only. */
    @PostMapping("/api/v1/team-roles")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: CreateTeamRoleRequest,
    ) = TeamRoleResponse(roles.create(actor, TeamRole(request.key.trim()), request.settings()))

    /** Changes a role's labels, description, order or whether it can still be given. Site admins only. */
    @PutMapping("/api/v1/team-roles/{key}")
    fun update(
        actor: Actor,
        @PathVariable key: String,
        @RequestBody request: UpdateTeamRoleRequest,
    ) = TeamRoleResponse(
        roles.update(
            actor,
            TeamRole(key),
            TeamRoleSettings(request.labels, request.description, request.active ?: true, request.sortOrder ?: 0),
        ),
    )
}

internal data class CreatePolicyRequest(
    /** Team role key, e.g. `MEMBER`. */
    val role: String,
    /** Null: all types. Otherwise the policy also applies to every subtype. */
    val typeId: UUID? = null,
    /** Lifecycle state key, e.g. `RELEASED`. Null: every state. */
    val state: String? = null,
    /** Default ALLOW. DENY takes the permissions away again, whatever any ALLOW says. */
    val effect: PolicyEffect? = null,
    val permissions: List<Permission>,
)

internal data class UpdatePolicyRequest(
    val permissions: List<Permission>,
)

internal data class PolicyResponse(
    val id: UUID,
    val contextId: UUID,
    val role: String,
    val typeId: UUID?,
    val state: String?,
    val effect: PolicyEffect,
    val permissions: List<Permission>,
    /** Defined on a parent context, not the one asked about. */
    val inherited: Boolean,
    val createdAt: Instant,
    val createdBy: String,
) {
    constructor(p: AccessPolicy, askedAbout: ContextId) : this(
        p.id.value,
        p.contextId.value,
        p.role.key,
        p.typeId?.value,
        p.state,
        p.effect,
        p.permissions.sortedBy { it.bit },
        p.contextId != askedAbout,
        p.createdAt,
        p.createdBy,
    )
}

internal data class PermissionExplanationResponse(
    val permission: Permission,
    val granted: Boolean,
    val allowedBy: List<PolicyResponse>,
    val deniedBy: List<PolicyResponse>,
)

internal data class ExplanationResponse(
    val principalId: String,
    val contextId: UUID,
    val typeId: UUID?,
    val state: String?,
    /** Memberships that apply here (this context and its parents). */
    val roles: List<MemberResponse>,
    /** True only when explaining yourself as a site administrator: policies don't limit you. */
    val siteAdmin: Boolean,
    val permissions: List<PermissionExplanationResponse>,
) {
    constructor(e: AccessExplanation) : this(
        e.principalId.value,
        e.target.contextId.value,
        e.target.typeId?.value,
        e.target.state,
        e.roles.map(::MemberResponse),
        e.siteAdmin,
        e.permissions.map { p ->
            PermissionExplanationResponse(
                p.permission,
                p.granted,
                p.allowedBy.map { PolicyResponse(it, e.target.contextId) },
                p.deniedBy.map { PolicyResponse(it, e.target.contextId) },
            )
        },
    )
}

internal data class TeamRoleResponse(
    val key: String,
    val labels: Map<String, String>,
    val description: String?,
    val system: Boolean,
    val active: Boolean,
    val sortOrder: Int,
) {
    constructor(r: TeamRoleDefinition) : this(r.role.key, r.labels, r.description, r.system, r.active, r.sortOrder)
}

internal data class CreateTeamRoleRequest(
    /** UPPER_SNAKE_CASE, e.g. `DESIGN_ENGINEER`. Can't be changed later. */
    val key: String,
    val labels: Map<String, String>,
    val description: String? = null,
    val sortOrder: Int? = null,
) {
    fun settings() = TeamRoleSettings(labels, description, true, sortOrder ?: 0)
}

internal data class UpdateTeamRoleRequest(
    val labels: Map<String, String>,
    val description: String? = null,
    val active: Boolean? = null,
    val sortOrder: Int? = null,
)
