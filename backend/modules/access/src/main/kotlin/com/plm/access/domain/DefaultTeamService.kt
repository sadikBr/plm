package com.plm.access.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.Permission
import com.plm.access.api.TeamMember
import com.plm.access.api.TeamMemberPut
import com.plm.access.api.TeamMemberRemoved
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.access.infra.AccessRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional
internal class DefaultTeamService(
    private val repository: AccessRepository,
    private val contexts: ContextService,
    private val access: AccessService,
    private val events: DomainEvents,
    private val clock: Clock,
) : TeamService {
    @Transactional(readOnly = true)
    override fun members(
        actor: Actor,
        contextId: ContextId,
    ): List<TeamMember> {
        val context = contexts.get(actor, contextId)
        return repository.members(context.id)
    }

    @Transactional(readOnly = true)
    override fun effectiveMembers(
        actor: Actor,
        contextId: ContextId,
    ): List<TeamMember> {
        val context = contexts.get(actor, contextId)
        return repository.effectiveMembers(context.id)
    }

    override fun put(
        actor: Actor,
        contextId: ContextId,
        principalId: PrincipalId,
        role: TeamRole,
    ): TeamMember {
        val context = contexts.get(actor, contextId)
        access.require(actor, AccessTarget(context.id), Permission.MANAGE)
        val definition = repository.role(actor.tenant, role)
        if (definition == null || !definition.active) {
            throw ValidationException(
                "UNKNOWN_ROLE",
                "There is no active role '${role.key}'",
                mapOf("role" to role.key),
            )
        }
        val previous = repository.member(context.id, principalId)
        val member = TeamMember(context.id, principalId, role, clock.instant(), actor.principal.value)
        repository.putMember(actor.tenant, member)
        events.publish(actor, TeamMemberPut(context.id.value, principalId.value, role.key, previous?.role?.key))
        return member
    }

    override fun remove(
        actor: Actor,
        contextId: ContextId,
        principalId: PrincipalId,
    ) {
        val context = contexts.get(actor, contextId)
        access.require(actor, AccessTarget(context.id), Permission.MANAGE)
        val existing =
            repository.member(context.id, principalId)
                ?: throw NotFoundException("MEMBER_NOT_FOUND", "$principalId is not on this team")
        repository.removeMember(context.id, principalId)
        events.publish(actor, TeamMemberRemoved(context.id.value, principalId.value, existing.role.key))
    }
}
