package com.plm.types.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.types.api.AttributeDefined
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeId
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeSettings
import com.plm.types.api.AttributeUpdated
import com.plm.types.api.AttributeValidator
import com.plm.types.api.DataType
import com.plm.types.api.DefineAttribute
import com.plm.types.api.TypeId
import com.plm.types.infra.AttributeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.regex.PatternSyntaxException

/** camelCase, starting with a lowercase letter: `mass`, `defaultUnit`, `threadSize`. */
internal val ATTRIBUTE_NAME = Regex("^[a-z][A-Za-z0-9]{0,62}$")

private val TEXTUAL = setOf(DataType.STRING, DataType.TEXT)
private val NUMERIC = setOf(DataType.INTEGER, DataType.REAL, DataType.QUANTITY)

@Service
@Transactional
internal class DefaultAttributeService(
    private val repository: AttributeRepository,
    private val events: DomainEvents,
) : AttributeService,
    AttributeValidator {
    @Transactional(readOnly = true)
    override fun effective(
        actor: Actor,
        typeId: TypeId,
        includeInactive: Boolean,
    ): List<AttributeDefinition> = definitions(actor, typeId).filter { includeInactive || it.active }

    @Transactional(readOnly = true)
    override fun validate(
        actor: Actor,
        typeId: TypeId,
        values: Map<String, Any?>,
        previous: Map<String, Any?>?,
    ): Map<String, Any?> {
        val definitions = definitions(actor, typeId)
        val result = rules(actor, definitions).validate(values, previous)
        if (result.errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_ATTRIBUTES",
                result.errors.joinToString("; ") { it.message },
                mapOf(
                    "errors" to
                        result.errors.map {
                            mapOf(
                                "attribute" to it.attribute,
                                "code" to it.code,
                                "message" to it.message,
                            )
                        },
                ),
            )
        }
        return result.values
    }

    override fun define(
        actor: Actor,
        command: DefineAttribute,
    ): AttributeDefinition {
        requireAdmin(actor)
        val lineage = lineage(actor, command.typeId)
        val name = command.name.trim()
        val def =
            command.settings.applyTo(
                AttributeDefinition(
                    id = AttributeId(UuidV7.generate()),
                    typeId = command.typeId,
                    name = name,
                    labels = emptyMap(),
                    description = null,
                    dataType = command.dataType,
                    multiValued = command.multiValued,
                    required = false,
                    immutable = false,
                    minLength = null,
                    maxLength = null,
                    pattern = null,
                    minValue = null,
                    maxValue = null,
                    enumerationId = command.enumerationId,
                    dimension = command.dimension?.trim()?.uppercase(),
                    displayUnit = null,
                    defaultValue = null,
                    sortOrder = 0,
                    active = true,
                    system = false,
                ),
            )

        val errors = mutableListOf<String>()
        if (!ATTRIBUTE_NAME.matches(name)) {
            errors += "Name must be camelCase: start with a lowercase letter, then letters and digits (max 63)"
        }
        // Names are unique along the whole hierarchy: no clash with ancestors (inherited) or subtypes.
        val related = repository.definitionsOf((lineage + repository.subtree(command.typeId)).distinct())
        if (related.any { it.name.equals(name, ignoreCase = true) }) {
            errors += "An attribute named '$name' already exists on this type, a parent type or a subtype"
        }
        errors += definitionErrors(actor, def)
        failIfAny(errors)

        repository.insertDefinition(actor.tenant, def)
        events.publish(actor, AttributeDefined(def.id.value, def.typeId.value, def.name, def.dataType))
        return def
    }

    override fun update(
        actor: Actor,
        id: AttributeId,
        settings: AttributeSettings,
    ): AttributeDefinition {
        requireAdmin(actor)
        val before =
            repository.findDefinition(actor.tenant, id)
                ?: throw NotFoundException(
                    "ATTRIBUTE_NOT_FOUND",
                    "Attribute $id does not exist",
                    mapOf(
                        "attributeId" to id.value,
                    ),
                )
        val after = settings.applyTo(before)
        failIfAny(definitionErrors(actor, after))
        repository.updateDefinition(after)
        events.publish(
            actor,
            AttributeUpdated(after.id.value, after.typeId.value, after.name, before.settingsMap(), after.settingsMap()),
        )
        return after
    }

    /** Whether the constraints fit the data type and refer to things that exist. */
    private fun definitionErrors(
        actor: Actor,
        def: AttributeDefinition,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (def.labels["en"].isNullOrBlank()) errors += "An English label (labels.en) is required"
        if (def.labels.values.any { it.length > 200 }) errors += "Labels must be at most 200 characters"

        if (def.dataType !in TEXTUAL && (def.minLength != null || def.pattern != null)) {
            errors += "minLength and pattern apply to STRING and TEXT only"
        }
        if (def.dataType !in TEXTUAL + DataType.URL && def.maxLength != null) {
            errors += "maxLength applies to STRING, TEXT and URL only"
        }
        if (def.minLength != null && def.maxLength != null && def.minLength > def.maxLength) {
            errors += "minLength must not exceed maxLength"
        }
        def.pattern?.let {
            try {
                Regex(it)
            } catch (_: PatternSyntaxException) {
                errors += "pattern is not a valid regular expression"
            }
        }
        if (def.dataType !in NUMERIC && (def.minValue != null || def.maxValue != null)) {
            errors += "minValue and maxValue apply to INTEGER, REAL and QUANTITY only"
        }
        if (def.minValue != null && def.maxValue != null && def.minValue > def.maxValue) {
            errors += "minValue must not exceed maxValue"
        }

        val units = repository.units(actor.tenant)
        if (def.dataType == DataType.QUANTITY) {
            if (def.dimension == null || units.none { it.dimension == def.dimension }) {
                errors +=
                    "QUANTITY needs a known dimension: ${units.map { it.dimension }.distinct().sorted().joinToString()}"
            } else if (def.displayUnit != null &&
                units.none { it.code == def.displayUnit && it.dimension == def.dimension }
            ) {
                errors += "displayUnit '${def.displayUnit}' is not a ${def.dimension.lowercase()} unit"
            }
        } else if (def.dimension != null || def.displayUnit != null) {
            errors += "dimension and displayUnit apply to QUANTITY only"
        }

        if (def.dataType == DataType.ENUMERATION) {
            if (def.enumerationId == null ||
                repository.enumerations(actor.tenant, listOf(def.enumerationId)).isEmpty()
            ) {
                errors += "ENUMERATION needs an existing enumerationId"
            }
        } else if (def.enumerationId != null) {
            errors += "enumerationId applies to ENUMERATION only"
        }

        if (errors.isEmpty() && def.defaultValue != null) {
            rules(actor, listOf(def)).check(def, def.defaultValue)?.let { errors += "Default value: ${it.message}" }
        }
        return errors
    }

    private fun failIfAny(errors: List<String>) {
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_ATTRIBUTE_DEFINITION",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
    }

    private fun lineage(
        actor: Actor,
        typeId: TypeId,
    ): List<TypeId> =
        repository.lineage(actor.tenant, typeId).ifEmpty {
            throw NotFoundException("TYPE_NOT_FOUND", "Type $typeId does not exist", mapOf("typeId" to typeId.value))
        }

    /** Effective definitions (including inactive): ancestors' first, then by sort order and name. */
    private fun definitions(
        actor: Actor,
        typeId: TypeId,
    ): List<AttributeDefinition> {
        val lineage = lineage(actor, typeId)
        val depth = lineage.withIndex().associate { (i, t) -> t to i }
        return repository
            .definitionsOf(lineage)
            .sortedWith(compareBy({ depth.getValue(it.typeId) }, { it.sortOrder }, { it.name }))
    }

    private fun rules(
        actor: Actor,
        definitions: List<AttributeDefinition>,
    ): AttributeRules {
        val enumerationIds = definitions.mapNotNull { it.enumerationId }.toSet()
        val enumerations = repository.enumerations(actor.tenant, enumerationIds).associateBy { it.id }
        val units = repository.units(actor.tenant).associateBy { it.code }
        return AttributeRules(definitions, enumerations, units)
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators can change attribute definitions",
            )
        }
    }
}

private fun AttributeSettings.applyTo(def: AttributeDefinition) =
    def.copy(
        labels = labels.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
        description = description?.trim()?.ifEmpty { null },
        required = required,
        immutable = immutable,
        minLength = minLength,
        maxLength = maxLength,
        pattern = pattern?.ifEmpty { null },
        minValue = minValue,
        maxValue = maxValue,
        displayUnit = displayUnit?.ifEmpty { null },
        defaultValue = defaultValue,
        sortOrder = sortOrder,
        active = active,
    )

private fun AttributeDefinition.settingsMap(): Map<String, Any?> =
    mapOf(
        "labels" to labels,
        "description" to description,
        "required" to required,
        "immutable" to immutable,
        "minLength" to minLength,
        "maxLength" to maxLength,
        "pattern" to pattern,
        "minValue" to minValue?.toPlainString(),
        "maxValue" to maxValue?.toPlainString(),
        "displayUnit" to displayUnit,
        "defaultValue" to defaultValue,
        "sortOrder" to sortOrder,
        "active" to active,
    )
