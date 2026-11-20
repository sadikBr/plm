package com.plm.lifecycle.domain

import com.plm.lifecycle.api.CreateLifecycleTemplate
import com.plm.lifecycle.api.LifecycleAssigned
import com.plm.lifecycle.api.LifecycleService
import com.plm.lifecycle.api.LifecycleTemplate
import com.plm.lifecycle.api.LifecycleTemplateId
import com.plm.lifecycle.infra.LifecycleRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val STATE_KEY = Regex("^[A-Z][A-Z0-9_]{0,39}$")

@Service
@Transactional
internal class DefaultLifecycleService(
    private val repository: LifecycleRepository,
    private val types: TypeService,
    private val events: DomainEvents,
) : LifecycleService {
    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<LifecycleTemplate> = repository.templates(actor.tenant)

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: LifecycleTemplateId,
    ): LifecycleTemplate =
        repository.templates(actor.tenant, listOf(id)).singleOrNull()
            ?: throw NotFoundException(
                "LIFECYCLE_NOT_FOUND",
                "Lifecycle $id does not exist",
                mapOf(
                    "lifecycleId" to id.value,
                ),
            )

    @Transactional(readOnly = true)
    override fun forType(
        actor: Actor,
        typeId: TypeId,
    ): LifecycleTemplate {
        val lineage = types.lineage(actor, typeId)
        val assigned = repository.assignments(lineage)
        val nearest = lineage.reversed().firstNotNullOfOrNull { assigned[it] } ?: LifecycleTemplateId.BASIC
        return get(actor, nearest)
    }

    override fun assign(
        actor: Actor,
        typeId: TypeId,
        templateId: LifecycleTemplateId,
    ): LifecycleTemplate {
        requireAdmin(actor)
        types.lineage(actor, typeId) // 404 for unknown types
        val template = get(actor, templateId)
        repository.assign(actor.tenant, typeId, templateId)
        events.publish(actor, LifecycleAssigned(typeId.value, templateId.value, template.name))
        return template
    }

    override fun create(
        actor: Actor,
        command: CreateLifecycleTemplate,
    ): LifecycleTemplate {
        requireAdmin(actor)
        val template =
            LifecycleTemplate(
                LifecycleTemplateId(UuidV7.generate()),
                command.name.trim(),
                command.labels,
                command.initialState,
                command.states,
                command.transitions,
                system = false,
            )
        val errors = validate(template)
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_LIFECYCLE",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
        repository.insert(actor.tenant, template)
        return template
    }

    private fun validate(t: LifecycleTemplate): List<String> {
        val errors = mutableListOf<String>()
        if (t.name.isEmpty() || t.name.length > 100) errors += "Name must be 1-100 characters"
        if (t.labels["en"].isNullOrBlank()) errors += "An English label (labels.en) is required"
        if (t.states.isEmpty()) errors += "A lifecycle needs at least one state"
        val keys = t.states.map { it.key }
        keys
            .groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .forEach { errors += "State '$it' appears twice" }
        t.states.forEach { s ->
            if (!STATE_KEY.matches(s.key)) errors += "State key '${s.key}' must be UPPER_SNAKE_CASE"
            if (s.labels["en"].isNullOrBlank()) errors += "State '${s.key}' needs an English label"
        }
        if (t.initialState !in keys) {
            errors += "The initial state must be one of the states"
        } else if (t.state(t.initialState)?.editable !=
            true
        ) {
            errors += "The initial state must be editable, or new objects could never be edited"
        }
        t.transitions.forEach { tr ->
            if (tr.from !in keys || tr.to !in keys) errors += "Transition ${tr.from} -> ${tr.to} uses an unknown state"
            if (tr.from == tr.to) errors += "Transition ${tr.from} -> ${tr.to} goes nowhere"
        }
        // Every state should be reachable from the initial state, or objects can never get there.
        if (errors.isEmpty()) {
            val reached = mutableSetOf(t.initialState)
            var frontier = setOf(t.initialState)
            while (frontier.isNotEmpty()) {
                frontier =
                    t.transitions
                        .filter { it.from in frontier && it.to !in reached }
                        .map { it.to }
                        .toSet()
                reached += frontier
            }
            (keys - reached).forEach { errors += "State '$it' can't be reached from ${t.initialState}" }
        }
        return errors
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators can change lifecycles",
            )
        }
    }
}
