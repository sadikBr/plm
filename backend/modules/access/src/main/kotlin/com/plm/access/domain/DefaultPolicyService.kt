package com.plm.access.domain

import com.plm.access.api.AccessExplanation
import com.plm.access.api.AccessPolicy
import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Context
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.CreatePolicy
import com.plm.access.api.Permission
import com.plm.access.api.PermissionExplanation
import com.plm.access.api.PolicyChanged
import com.plm.access.api.PolicyEffect
import com.plm.access.api.PolicyId
import com.plm.access.api.PolicyService
import com.plm.access.api.TeamRole
import com.plm.access.infra.AccessRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

private val STATE_KEY = Regex("^[A-Z][A-Z0-9_]{0,39}$")

@Service
@Transactional
internal class DefaultPolicyService(
    private val repository: AccessRepository,
    private val contexts: ContextService,
    private val access: AccessService,
    private val types: TypeService,
    private val events: DomainEvents,
    private val clock: Clock,
) : PolicyService {
    @Transactional(readOnly = true)
    override fun policies(
        actor: Actor,
        contextId: ContextId,
    ): List<AccessPolicy> {
        val context = contexts.get(actor, contextId)
        access.require(actor, AccessTarget(context.id), Permission.MANAGE)
        return repository.policiesInEffect(context.id)
    }

    override fun create(
        actor: Actor,
        contextId: ContextId,
        command: CreatePolicy,
    ): AccessPolicy {
        val context = contexts.get(actor, contextId)
        requireCanChangePolicies(actor, context)
        val state = command.state?.trim()?.ifEmpty { null }
        val errors = mutableListOf<String>()
        if (command.permissions.isEmpty()) errors += "Choose at least one permission"
        if (state != null && !STATE_KEY.matches(state)) {
            errors += "State must be a lifecycle state key such as RELEASED"
        }
        if (command.typeId != null && types.list(actor).none { it.id == command.typeId }) {
            errors += "No type ${command.typeId}"
        }
        if (errors.isNotEmpty()) {
            throw ValidationException("INVALID_POLICY", errors.joinToString("; "), mapOf("errors" to errors))
        }
        requireAssignable(actor, command.role)

        val policy =
            AccessPolicy(
                id = PolicyId(UuidV7.generate()),
                contextId = context.id,
                role = command.role,
                typeId = command.typeId,
                state = state,
                effect = command.effect,
                permissions = command.permissions,
                createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
                createdBy = actor.principal.value,
            )
        repository.insertPolicy(actor.tenant, policy)
        events.publish(actor, policy.changed(previous = null))
        return policy
    }

    override fun update(
        actor: Actor,
        id: PolicyId,
        permissions: Set<Permission>,
    ): AccessPolicy {
        val before = find(actor, id)
        requireCanChangePolicies(actor, contexts.get(actor, before.contextId))
        if (permissions.isEmpty()) {
            throw ValidationException(
                "INVALID_POLICY",
                "Choose at least one permission, or delete the policy",
                mapOf("errors" to listOf("Choose at least one permission, or delete the policy")),
            )
        }
        val after = before.copy(permissions = permissions)
        repository.updatePolicyPermissions(id, permissions)
        events.publish(actor, after.changed(previous = before.permissions))
        return after
    }

    override fun delete(
        actor: Actor,
        id: PolicyId,
    ) {
        val policy = find(actor, id)
        requireCanChangePolicies(actor, contexts.get(actor, policy.contextId))
        repository.deletePolicy(id)
        events.publish(actor, policy.changed(previous = policy.permissions, deleted = true))
    }

    @Transactional(readOnly = true)
    override fun explain(
        actor: Actor,
        principalId: PrincipalId,
        target: AccessTarget,
    ): AccessExplanation {
        val context = contexts.get(actor, target.contextId)
        val self = principalId == actor.principal
        if (!self) access.require(actor, AccessTarget(context.id), Permission.MANAGE)
        val siteAdmin = self && actor.siteAdmin
        val matching = repository.matchingPolicies(actor.tenant, principalId, context.id, target.typeId, target.state)
        return AccessExplanation(
            principalId = principalId,
            target = target,
            roles = repository.effectiveMembers(context.id, principalId),
            siteAdmin = siteAdmin,
            permissions =
                Permission.entries.map { permission ->
                    fun by(effect: PolicyEffect) =
                        matching.filter { it.effect == effect && permission in it.permissions }
                    val allowedBy = by(PolicyEffect.ALLOW)
                    val deniedBy = by(PolicyEffect.DENY)
                    PermissionExplanation(
                        permission = permission,
                        granted = siteAdmin || (allowedBy.isNotEmpty() && deniedBy.isEmpty()),
                        allowedBy = allowedBy,
                        deniedBy = deniedBy,
                    )
                },
        )
    }

    private fun find(
        actor: Actor,
        id: PolicyId,
    ): AccessPolicy {
        val policy = repository.policy(actor.tenant, id)
        // A policy on a context the caller can't see doesn't exist, as far as they know.
        val visible = policy != null && runCatching { contexts.get(actor, policy.contextId) }.isSuccess
        if (!visible) {
            throw NotFoundException(
                "POLICY_NOT_FOUND",
                "Policy $id does not exist",
                mapOf(
                    "policyId" to id.value,
                ),
            )
        }
        return policy
    }

    /** Policies on the site context apply to everyone, so only site administrators change them. */
    private fun requireCanChangePolicies(
        actor: Actor,
        context: Context,
    ) {
        if (context.parentId == null) {
            if (!actor.siteAdmin) {
                throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators can change site-wide policies")
            }
        } else {
            access.require(actor, AccessTarget(context.id), Permission.MANAGE)
        }
    }

    private fun requireAssignable(
        actor: Actor,
        role: TeamRole,
    ) {
        val definition = repository.role(actor.tenant, role)
        if (definition == null || !definition.active) {
            throw ValidationException(
                "UNKNOWN_ROLE",
                "There is no active role '${role.key}'",
                mapOf("role" to role.key),
            )
        }
    }

    private fun AccessPolicy.changed(
        previous: Set<Permission>?,
        deleted: Boolean = false,
    ) = PolicyChanged(
        policyId = id.value,
        contextId = contextId.value,
        role = role.key,
        typeId = typeId?.value,
        state = state,
        effect = effect.name,
        permissions = if (deleted) emptyList() else permissions.map { it.name },
        previousPermissions = previous?.map { it.name },
        deleted = deleted,
    )
}
