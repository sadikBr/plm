package com.plm.access.domain

import com.plm.access.api.Context
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.infra.ContextRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val MAX_NAME_LENGTH = 200

@Service
@Transactional
internal class DefaultContextService(
    private val contexts: ContextRepository,
    private val clock: Clock,
) : ContextService {
    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: ContextId,
    ): Context =
        contexts.find(actor.tenant, id)
            ?: throw NotFoundException(
                "CONTEXT_NOT_FOUND",
                "Context $id does not exist",
                mapOf("contextId" to id.value),
            )

    @Transactional(readOnly = true)
    override fun children(
        actor: Actor,
        parentId: ContextId,
    ): List<Context> = contexts.children(actor.tenant, get(actor, parentId).id)

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
        return context
    }
}
