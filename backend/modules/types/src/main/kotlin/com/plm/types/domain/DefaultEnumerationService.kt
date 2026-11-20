package com.plm.types.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.types.api.Enumeration
import com.plm.types.api.EnumerationChanged
import com.plm.types.api.EnumerationId
import com.plm.types.api.EnumerationService
import com.plm.types.api.EnumerationValue
import com.plm.types.api.Labels
import com.plm.types.api.UnitOfMeasure
import com.plm.types.infra.AttributeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val VALUE_KEY = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$")

private val UNIT_CODE = Regex("^[^\\s]{1,20}$")
private val DIMENSION = Regex("^[A-Z][A-Z0-9_]{1,39}$")

@Service
@Transactional
internal class DefaultEnumerationService(
    private val repository: AttributeRepository,
    private val events: DomainEvents,
) : EnumerationService {
    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<Enumeration> = repository.enumerations(actor.tenant)

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: EnumerationId,
    ): Enumeration =
        repository.enumerations(actor.tenant, listOf(id)).singleOrNull()
            ?: throw NotFoundException(
                "ENUMERATION_NOT_FOUND",
                "Enumeration $id does not exist",
                mapOf(
                    "enumerationId" to id.value,
                ),
            )

    override fun create(
        actor: Actor,
        name: String,
        labels: Labels,
        values: List<EnumerationValue>,
    ): Enumeration {
        requireAdmin(actor)
        val enumeration =
            Enumeration(EnumerationId(UuidV7.generate()), name.trim(), clean(labels), false, values.map(::clean))
        val errors = validate(enumeration).toMutableList()
        if (!ATTRIBUTE_NAME.matches(enumeration.name)) errors.add(0, "Name must be camelCase, e.g. 'threadSize'")
        failIfAny(errors)
        repository.insertEnumeration(actor.tenant, enumeration)
        publish(actor, enumeration)
        return enumeration
    }

    override fun update(
        actor: Actor,
        id: EnumerationId,
        labels: Labels,
        values: List<EnumerationValue>,
    ): Enumeration {
        requireAdmin(actor)
        val existing = get(actor, id)
        val updated = existing.copy(labels = clean(labels), values = values.map(::clean))
        val errors = validate(updated).toMutableList()
        val removed = existing.values.map { it.key } - updated.values.map { it.key }.toSet()
        if (removed.isNotEmpty()) {
            throw ValidationException(
                "ENUMERATION_VALUE_REMOVED",
                "Values can't be removed, because existing objects may use them. Deactivate instead: ${removed.joinToString()}",
                mapOf("keys" to removed),
            )
        }
        failIfAny(errors)
        repository.updateEnumeration(updated)
        publish(actor, updated)
        return updated
    }

    @Transactional(readOnly = true)
    override fun units(actor: Actor): List<UnitOfMeasure> = repository.units(actor.tenant)

    override fun createUnit(
        actor: Actor,
        unit: UnitOfMeasure,
    ): UnitOfMeasure {
        requireAdmin(actor)
        val code = unit.code.trim()
        val dimension = unit.dimension.trim().uppercase()
        val errors = mutableListOf<String>()
        if (!UNIT_CODE.matches(code)) errors += "Code: 1-20 characters, no spaces (e.g. mm, lbf, kWh)"
        if (!DIMENSION.matches(dimension)) errors += "Dimension: UPPER_SNAKE_CASE, e.g. TORQUE"
        if (unit.labels["en"].isNullOrBlank()) errors += "An English label (labels.en) is required"
        if (unit.factor.signum() <= 0) errors += "Factor must be greater than 0"
        val existing = repository.units(actor.tenant).filter { it.dimension == dimension }
        if (existing.isEmpty() && (unit.factor.compareTo(java.math.BigDecimal.ONE) != 0 || unit.offset.signum() != 0)) {
            errors += "$dimension is a new dimension: its first unit is the base unit (factor 1, offset 0)"
        }
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_UNIT",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
        val created = unit.copy(code = code, dimension = dimension, system = false)
        repository.insertUnit(actor.tenant, created)
        return created
    }

    private fun validate(enumeration: Enumeration): List<String> {
        val errors = mutableListOf<String>()
        if (enumeration.labels["en"].isNullOrBlank()) errors += "An English label (labels.en) is required"
        if (enumeration.values.isEmpty()) errors += "An enumeration needs at least one value"
        if (enumeration.values.none { it.active }) errors += "At least one value must be active"
        enumeration.values.groupBy { it.key }.filterValues { it.size > 1 }.keys.forEach {
            errors +=
                "Key '$it' appears twice"
        }
        enumeration.values.forEach { v ->
            if (!VALUE_KEY.matches(v.key)) errors += "Key '${v.key}' must be letters, digits, '.', '_' or '-' (max 100)"
            if (v.labels["en"].isNullOrBlank()) errors += "Value '${v.key}' needs an English label"
        }
        return errors
    }

    private fun failIfAny(errors: List<String>) {
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_ENUMERATION",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
    }

    private fun publish(
        actor: Actor,
        e: Enumeration,
    ) {
        events.publish(
            actor,
            EnumerationChanged(
                e.id.value,
                e.name,
                e.values.map { it.key },
                e.values.filter { it.active }.map { it.key },
            ),
        )
    }

    private fun clean(labels: Labels) = labels.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }

    private fun clean(value: EnumerationValue) = value.copy(key = value.key.trim(), labels = clean(value.labels))

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators can change enumerations",
            )
        }
    }
}
