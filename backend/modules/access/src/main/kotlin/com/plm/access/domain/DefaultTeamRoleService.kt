package com.plm.access.domain

import com.plm.access.api.TeamRole
import com.plm.access.api.TeamRoleChanged
import com.plm.access.api.TeamRoleDefinition
import com.plm.access.api.TeamRoleService
import com.plm.access.api.TeamRoleSettings
import com.plm.access.infra.AccessRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val ROLE_KEY = Regex("^[A-Z][A-Z0-9_]{1,39}$")

@Service
@Transactional
internal class DefaultTeamRoleService(
    private val repository: AccessRepository,
    private val events: DomainEvents,
) : TeamRoleService {
    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<TeamRoleDefinition> = repository.roles(actor.tenant)

    override fun create(
        actor: Actor,
        role: TeamRole,
        settings: TeamRoleSettings,
    ): TeamRoleDefinition {
        requireAdmin(actor)
        val errors = settingsErrors(settings)
        if (!ROLE_KEY.matches(
                role.key,
            )
        ) {
            errors.add(0, "Key must be UPPER_SNAKE_CASE, 2-40 characters, e.g. DESIGN_ENGINEER")
        }
        failIfAny(errors)
        val definition = settings.applyTo(role, system = false)
        repository.insertRole(actor.tenant, definition)
        events.publish(actor, TeamRoleChanged(role.key, definition.labels, definition.active, created = true))
        return definition
    }

    override fun update(
        actor: Actor,
        role: TeamRole,
        settings: TeamRoleSettings,
    ): TeamRoleDefinition {
        requireAdmin(actor)
        val before =
            repository.role(actor.tenant, role)
                ?: throw NotFoundException(
                    "ROLE_NOT_FOUND",
                    "There is no role '${role.key}'",
                    mapOf("role" to role.key),
                )
        val errors = settingsErrors(settings)
        if (before.system && !settings.active) errors += "Out-of-the-box roles can't be deactivated"
        failIfAny(errors)
        val after = settings.applyTo(role, system = before.system)
        repository.updateRole(actor.tenant, after)
        events.publish(actor, TeamRoleChanged(role.key, after.labels, after.active, created = false))
        return after
    }

    private fun settingsErrors(settings: TeamRoleSettings): MutableList<String> {
        val errors = mutableListOf<String>()
        if (settings.labels["en"].isNullOrBlank()) errors += "An English label (labels.en) is required"
        if (settings.labels.values.any { it.length > 100 }) errors += "Labels must be at most 100 characters"
        if ((settings.description?.length ?: 0) > 1000) errors += "Description must be at most 1000 characters"
        return errors
    }

    private fun TeamRoleSettings.applyTo(
        role: TeamRole,
        system: Boolean,
    ) = TeamRoleDefinition(
        role = role,
        labels = labels.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
        description = description?.trim()?.ifEmpty { null },
        system = system,
        active = active,
        sortOrder = sortOrder,
    )

    private fun failIfAny(errors: List<String>) {
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_ROLE",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators can change team roles",
            )
        }
    }
}
