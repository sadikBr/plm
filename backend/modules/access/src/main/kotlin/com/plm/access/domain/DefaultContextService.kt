package com.plm.access.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Context
import com.plm.access.api.ContextCreated
import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.Permission
import com.plm.access.api.TeamMember
import com.plm.access.api.TeamRole
import com.plm.access.infra.AccessRepository
import com.plm.access.infra.ContextRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val MAX_NAME_LENGTH = 200

@Service
@Transactional
internal class DefaultContextService(
    private val contexts: ContextRepository,
    private val accessRepository: AccessRepository,
    private val access: AccessService,
    private val events: DomainEvents,
    private val clock: Clock,
) : ContextService {
    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: ContextId,
    ): Context {
        val context = contexts.find(actor.tenant, id)
        if (context == null || !isVisible(actor, context)) {
            throw NotFoundException("CONTEXT_NOT_FOUND", "Context $id does not exist", mapOf("contextId" to id.value))
        }
        return context
    }

    @Transactional(readOnly = true)
    override fun children(
        actor: Actor,
        parentId: ContextId,
    ): List<Context> = contexts.children(actor.tenant, get(actor, parentId).id).filter { isVisible(actor, it) }

    @Transactional(readOnly = true)
    override fun path(
        actor: Actor,
        id: ContextId,
    ): List<Context> = contexts.path(get(actor, id).id)

    @Transactional(readOnly = true)
    override fun search(
        actor: Actor,
        text: String,
        limit: Int,
        kind: ContextKind?,
    ): List<Context> {
        val trimmed = text.trim()
        // A blank text lists every visible context, but only of one kind: the whole tree is the navigator's job.
        if (trimmed.isEmpty() && kind == null) return emptyList()
        if (trimmed.length >
            100
        ) {
            throw ValidationException("INVALID_QUERY", "Search text must be at most 100 characters")
        }
        return contexts.search(
            actor.tenant,
            actor.principal.value,
            actor.siteAdmin,
            trimmed,
            limit.coerceIn(1, 500),
            kind,
        )
    }

    override fun create(
        actor: Actor,
        command: CreateContext,
    ): Context {
        val name = command.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw ValidationException(
                "INVALID_CONTEXT_NAME",
                "Context name must be 1-$MAX_NAME_LENGTH characters",
                mapOf("name" to command.name),
            )
        }
        val parent = get(actor, command.parentId)
        if (command.kind !in parent.kind.allowedChildren()) {
            throw ValidationException(
                "INVALID_CONTEXT_HIERARCHY",
                "A ${command.kind} cannot be created inside a ${parent.kind}",
                mapOf("parentKind" to parent.kind, "kind" to command.kind),
            )
        }
        if (command.kind == ContextKind.ORGANIZATION && !actor.siteAdmin) {
            throw ForbiddenException("ACCESS_DENIED", "Only site administrators can create organizations")
        }
        access.require(actor, AccessTarget(parent.id), Permission.MANAGE)

        val context =
            Context(
                id = ContextId(UuidV7.generate(clock)),
                parentId = parent.id,
                kind = command.kind,
                name = name,
                createdAt = clock.instant(),
                createdBy = actor.principal.value,
            )
        contexts.insert(actor.tenant, context)
        accessRepository.insertClosure(actor.tenant, context.id, parent.id)
        accessRepository.putMember(
            actor.tenant,
            TeamMember(context.id, actor.principal, TeamRole.MANAGER, context.createdAt, actor.principal.value),
        )
        events.publish(actor, ContextCreated(context.id.value, parent.id.value, context.kind, context.name))
        return context
    }

    private fun isVisible(
        actor: Actor,
        context: Context,
    ) = actor.siteAdmin || accessRepository.isVisible(actor.tenant, actor.principal, context.id)
}
