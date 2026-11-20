package com.plm.access.api

import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.types.api.TypeId
import java.time.Instant
import java.util.UUID

/** Localized labels by language tag; `en` is always present. */
typealias RoleLabels = Map<String, String>

data class TeamRoleDefinition(
    val role: TeamRole,
    val labels: RoleLabels,
    val description: String?,
    /** Shipped out of the box: can be relabelled, not deactivated. */
    val system: Boolean,
    /** Inactive roles can't be given to anyone new; existing members and policies keep working. */
    val active: Boolean,
    val sortOrder: Int,
)

data class TeamRoleSettings(
    val labels: RoleLabels,
    val description: String? = null,
    val active: Boolean = true,
    val sortOrder: Int = 0,
)

/**
 * The team roles of a tenant (roadmap 1.8). Reading is open to everyone; creating and changing
 * roles is for site administrators. Error codes: `ADMIN_REQUIRED`, `INVALID_ROLE`, `DUPLICATE_ROLE`,
 * `ROLE_NOT_FOUND`.
 */
interface TeamRoleService {
    fun list(actor: Actor): List<TeamRoleDefinition>

    /** [role] keys are UPPER_SNAKE_CASE, 2–40 characters, e.g. `DESIGN_ENGINEER`. */
    fun create(
        actor: Actor,
        role: TeamRole,
        settings: TeamRoleSettings,
    ): TeamRoleDefinition

    fun update(
        actor: Actor,
        role: TeamRole,
        settings: TeamRoleSettings,
    ): TeamRoleDefinition
}

enum class PolicyEffect { ALLOW, DENY }

@JvmInline
value class PolicyId(
    val value: UUID,
) {
    override fun toString() = value.toString()
}

/**
 * Role [role], held in [contextId] or any ancestor, gets ([PolicyEffect.ALLOW]) or loses
 * ([PolicyEffect.DENY]) [permissions] on objects in [contextId] and below, of [typeId] or any of its
 * subtypes (null: all types), in lifecycle [state] (null: every state).
 */
data class AccessPolicy(
    val id: PolicyId,
    val contextId: ContextId,
    val role: TeamRole,
    val typeId: TypeId?,
    val state: String?,
    val effect: PolicyEffect,
    val permissions: Set<Permission>,
    val createdAt: Instant,
    val createdBy: String,
)

data class CreatePolicy(
    val role: TeamRole,
    val typeId: TypeId? = null,
    val state: String? = null,
    val effect: PolicyEffect = PolicyEffect.ALLOW,
    val permissions: Set<Permission>,
)

/** Why a principal has (or doesn't have) one permission on a target. */
data class PermissionExplanation(
    val permission: Permission,
    val granted: Boolean,
    /** The ALLOW policies that grant it (any one is enough). */
    val allowedBy: List<AccessPolicy>,
    /** The DENY policies that take it away (any one is enough, and wins over every ALLOW). */
    val deniedBy: List<AccessPolicy>,
)

data class AccessExplanation(
    val principalId: PrincipalId,
    val target: AccessTarget,
    /** The principal's roles that apply here: memberships in the target context and its ancestors. */
    val roles: List<TeamMember>,
    /**
     * True when explaining yourself as a site administrator: you have every permission whatever the
     * policies say. (Whether someone else is a site administrator is known only to the identity provider.)
     */
    val siteAdmin: Boolean,
    /** In [Permission] order. [PermissionExplanation.granted] already accounts for [siteAdmin]. */
    val permissions: List<PermissionExplanation>,
)

/**
 * Access policies (roadmap 1.8). Policies on a context are managed by whoever has MANAGE there;
 * policies on the site context only by site administrators. Error codes: `ACCESS_DENIED`,
 * `ADMIN_REQUIRED`, `INVALID_POLICY`, `UNKNOWN_ROLE`, `TYPE_NOT_FOUND`, `DUPLICATE_POLICY`,
 * `POLICY_NOT_FOUND`.
 */
interface PolicyService {
    /** Policies in effect in a context: its own and its ancestors', root first. Needs MANAGE. */
    fun policies(
        actor: Actor,
        contextId: ContextId,
    ): List<AccessPolicy>

    fun create(
        actor: Actor,
        contextId: ContextId,
        command: CreatePolicy,
    ): AccessPolicy

    /** Replaces the permission bits of a policy; role, type, state and effect are its identity. */
    fun update(
        actor: Actor,
        id: PolicyId,
        permissions: Set<Permission>,
    ): AccessPolicy

    fun delete(
        actor: Actor,
        id: PolicyId,
    )

    /**
     * Explains [principalId]'s permissions on [target]: the roles they hold there, and for each
     * permission the policies that grant or deny it. Anyone may ask about themselves; asking about
     * others needs MANAGE on the target's context. Site administrators bypass policies, so their
     * explanation only reflects their team roles.
     */
    fun explain(
        actor: Actor,
        principalId: PrincipalId,
        target: AccessTarget,
    ): AccessExplanation
}
