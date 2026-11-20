package com.plm.types.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.types.api.CreateSubtype
import com.plm.types.api.TypeCreated
import com.plm.types.api.TypeDefinition
import com.plm.types.api.TypeId
import com.plm.types.api.TypeProperties
import com.plm.types.api.TypePropertiesChanged
import com.plm.types.api.TypeService
import com.plm.types.infra.TypeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private val TYPE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,99}$")

private val ICON = Regex("^[a-z][a-z-]{0,29}$")
private val LANGUAGE = Regex("^[a-z]{2,3}(-[A-Z]{2})?$")

@Service
@Transactional
internal class DefaultTypeService(
    private val types: TypeRepository,
    private val hierarchy: com.plm.types.infra.AttributeRepository,
    private val events: DomainEvents,
    private val clock: Clock,
) : TypeService {
    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: TypeId,
    ): TypeDefinition =
        types.find(actor.tenant, id)
            ?: throw NotFoundException("TYPE_NOT_FOUND", "Type $id does not exist", mapOf("typeId" to id.value))

    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<TypeDefinition> = types.list(actor.tenant)

    @Transactional(readOnly = true)
    override fun lineage(
        actor: Actor,
        id: TypeId,
    ): List<TypeId> =
        hierarchy.lineage(actor.tenant, id).ifEmpty {
            throw NotFoundException("TYPE_NOT_FOUND", "Type $id does not exist", mapOf("typeId" to id.value))
        }

    override fun createSubtype(
        actor: Actor,
        command: CreateSubtype,
    ): TypeDefinition {
        if (!actor.siteAdmin) {
            throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators can define types")
        }
        if (!TYPE_NAME.matches(command.name)) {
            throw ValidationException(
                "INVALID_TYPE_NAME",
                "Type name must start with a letter and contain only letters, digits and underscores",
                mapOf("name" to command.name),
            )
        }
        if (command.displayName.isBlank()) {
            throw ValidationException("INVALID_DISPLAY_NAME", "Display name must not be blank")
        }
        val parent = get(actor, command.parentId)
        val type =
            TypeDefinition(
                id = TypeId(UuidV7.generate(clock)),
                parentId = parent.id,
                name = command.name,
                displayName = command.displayName.trim(),
                baseClass = parent.baseClass,
            )
        types.insert(actor.tenant, type, clock.instant())
        events.publish(actor, TypeCreated(type.id.value, parent.id.value, type.name, type.baseClass))
        return type
    }

    override fun update(
        actor: Actor,
        id: TypeId,
        properties: TypeProperties,
    ): TypeDefinition {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators can define types")
        val before = get(actor, id)
        val errors = mutableListOf<String>()
        val displayName = properties.displayName.trim()
        if (displayName.isEmpty() || displayName.length > 100) errors += "Display name must be 1-100 characters"
        if ((properties.description?.length ?: 0) > 1000) errors += "Description must be at most 1000 characters"
        if (properties.icon != null && !ICON.matches(properties.icon)) errors += "Unknown icon '${properties.icon}'"
        properties.labels.forEach { (lang, label) ->
            if (!LANGUAGE.matches(lang)) errors += "'$lang' is not a language tag such as fr or pt-BR"
            if (label.isBlank() || label.length > 100) errors += "The $lang label must be 1-100 characters"
        }
        if (errors.isNotEmpty()) {
            throw ValidationException("INVALID_TYPE_PROPERTIES", errors.joinToString("; "), mapOf("errors" to errors))
        }
        val after =
            before.copy(
                displayName = displayName,
                description = properties.description?.trim()?.ifEmpty { null },
                labels = properties.labels.mapValues { it.value.trim() },
                icon = properties.icon,
                instantiable = properties.instantiable,
            )
        types.update(after)
        events.publish(actor, TypePropertiesChanged(id.value, after.name, after.displayName, after.instantiable))
        return after
    }
}
