package com.plm.extensions.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.extensions.api.ActionConfiguration
import com.plm.extensions.api.ActionConfigured
import com.plm.extensions.api.ActionDeclarations
import com.plm.extensions.api.ActionDefinition
import com.plm.extensions.api.ActionService
import com.plm.extensions.api.AvailableAction
import com.plm.extensions.api.RegisteredAction
import com.plm.extensions.infra.ActionConfigRepository
import com.plm.lifecycle.api.LifecycleService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val MAX_LABEL = 60

@Service
@Transactional
internal class DefaultActionService(
    declarations: List<ActionDeclarations>,
    private val configs: ActionConfigRepository,
    private val versioning: VersioningService,
    private val access: AccessService,
    private val types: TypeService,
    private val lifecycles: LifecycleService,
    private val events: DomainEvents,
    private val clock: Clock,
) : ActionService {
    private val declared: Map<String, ActionDefinition> =
        declarations.flatMap { it.actions() }.let { all ->
            val duplicates = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "Actions declared twice: $duplicates" }
            all.associateBy { it.id }
        }

    @Transactional(readOnly = true)
    override fun registry(actor: Actor): List<RegisteredAction> {
        requireAdmin(actor)
        return registered(actor)
    }

    override fun configure(
        actor: Actor,
        id: String,
        configuration: ActionConfiguration,
    ): RegisteredAction {
        requireAdmin(actor)
        val action = declared[id] ?: throw notFound(id)
        val clean = validate(actor, configuration)
        configs.put(actor.tenant, action.id, clean, clock.instant(), actor.principal.value)
        events.publish(actor, ActionConfigured(action.id, clean))
        return registered(actor).first { it.declared.id == id }
    }

    override fun reset(
        actor: Actor,
        id: String,
    ): RegisteredAction {
        requireAdmin(actor)
        if (id !in declared) throw notFound(id)
        configs.delete(actor.tenant, id)
        events.publish(actor, ActionConfigured(id, null))
        return registered(actor).first { it.declared.id == id }
    }

    @Transactional(readOnly = true)
    override fun available(
        actor: Actor,
        objectId: MasterId,
    ): List<AvailableAction> {
        val obj = versioning.get(actor, objectId)
        val situation =
            ObjectSituation(
                actor,
                obj,
                versioning.lifecycle(actor, objectId),
                access.permissions(actor, AccessTarget(obj.contextId, obj.typeId, obj.latest.state)),
            )
        val names = types.list(actor).associate { it.id to it.name }
        val lineage = types.lineage(actor, obj.typeId).mapNotNull { names[it] }.toSet()
        return registered(actor)
            .asSequence()
            .filterNot { it.hidden }
            .map { it.effective }
            .filter { it.types.isEmpty() || it.types.any { t -> t in lineage } }
            .filter { it.states.isEmpty() || obj.latest.state in it.states }
            .mapNotNull { action ->
                when (val availability = BuiltinAvailability.of(action.id, situation)) {
                    Availability.Hidden -> null
                    is Availability.Shown -> {
                        val reason =
                            availability.reason
                                ?: action.requires
                                    ?.takeIf { it !in situation.permissions }
                                    ?.let { "You need the ${it.name.lowercase()} permission for this" }
                        AvailableAction(
                            id = action.id,
                            label = action.label,
                            icon = action.icon,
                            placement = action.placement,
                            group = action.group,
                            handler = action.handler,
                            enabled = reason == null,
                            reason = reason,
                            options = availability.options,
                        )
                    }
                }
            }.toList()
    }

    private fun registered(actor: Actor): List<RegisteredAction> {
        val stored = configs.all(actor.tenant).associateBy { it.actionId }
        return declared.values
            .map {
                RegisteredAction(
                    it,
                    stored[it.id]?.configuration,
                    stored[it.id]?.updatedAt,
                    stored[it.id]?.updatedBy,
                )
            }.sortedWith(compareBy({ it.effective.placement }, { it.effective.order }, { it.declared.id }))
    }

    private fun validate(
        actor: Actor,
        c: ActionConfiguration,
    ): ActionConfiguration {
        val label = c.label?.trim()?.ifEmpty { null }
        if (label != null && label.length > MAX_LABEL) {
            throw ValidationException("INVALID_LABEL", "Labels must be at most $MAX_LABEL characters")
        }
        val typeNames = types.list(actor).map { it.name }.toSet()
        val unknownTypes = c.types.orEmpty().filter { it !in typeNames }
        if (unknownTypes.isNotEmpty()) {
            throw ValidationException(
                "UNKNOWN_TYPE",
                "No such types: ${unknownTypes.joinToString()}",
                mapOf(
                    "types" to unknownTypes,
                ),
            )
        }
        val stateKeys =
            lifecycles
                .list(actor)
                .flatMap { l -> l.states.map { it.key } }
                .toSet()
        val unknownStates = c.states.orEmpty().filter { it !in stateKeys }
        if (unknownStates.isNotEmpty()) {
            throw ValidationException(
                "INVALID_STATE",
                "No lifecycle has the states ${unknownStates.joinToString()}",
                mapOf("states" to unknownStates),
            )
        }
        return c.copy(label = label, types = c.types?.distinct(), states = c.states?.distinct())
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators can configure actions",
            )
        }
    }

    private fun notFound(id: String) = NotFoundException("ACTION_NOT_FOUND", "No action $id", mapOf("actionId" to id))
}
