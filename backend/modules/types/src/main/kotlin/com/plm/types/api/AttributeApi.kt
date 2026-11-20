package com.plm.types.api

import com.plm.platform.actor.Actor
import java.math.BigDecimal
import java.util.UUID

/** Localized labels by language tag, e.g. `{"en": "Mass", "fr": "Masse"}`. `en` is always present. */
typealias Labels = Map<String, String>

enum class DataType {
    /** Single-line text. Default max length 1000. */
    STRING,

    /** Multi-line text. Default max length 100 000. */
    TEXT,

    /** Whole number (64-bit). */
    INTEGER,

    /** Floating-point number. */
    REAL,

    /** A number with a unit, stored as `{"value": 12.5, "unit": "mm"}`. Unit must match the attribute's dimension. */
    QUANTITY,
    BOOLEAN,

    /** ISO date, `2026-11-20`. */
    DATE,

    /** ISO instant, stored normalized to UTC, `2026-11-20T10:15:30Z`. */
    TIMESTAMP,

    /** Absolute http(s) URL. */
    URL,

    /** One key of the attribute's enumeration. */
    ENUMERATION,
}

@JvmInline
value class AttributeId(
    val value: UUID,
) {
    override fun toString() = value.toString()
}

@JvmInline
value class EnumerationId(
    val value: UUID,
) {
    override fun toString() = value.toString()
}

/**
 * A typed attribute on a type, inherited by all its subtypes ([typeId] is the type that defines it).
 *
 * Constraints that don't apply to the [dataType] are always null. For QUANTITY, [minValue] and
 * [maxValue] are in the dimension's base unit (m, kg, s, K, …).
 */
data class AttributeDefinition(
    val id: AttributeId,
    val typeId: TypeId,
    val name: String,
    val labels: Labels,
    val description: String?,
    val dataType: DataType,
    val multiValued: Boolean,
    val required: Boolean,
    val immutable: Boolean,
    val minLength: Int?,
    val maxLength: Int?,
    val pattern: String?,
    val minValue: BigDecimal?,
    val maxValue: BigDecimal?,
    val enumerationId: EnumerationId?,
    val dimension: String?,
    val displayUnit: String?,
    val defaultValue: Any?,
    val sortOrder: Int,
    val active: Boolean,
    /** Shipped out of the box: can be constrained or deactivated, never removed. */
    val system: Boolean,
    /** For a dependent value list: the ENUMERATION attribute whose value decides the allowed values. */
    val dependsOn: String? = null,
    /** For a dependent value list: parent value key → allowed keys. Parent values not listed allow every value. */
    val valueFilter: Map<String, List<String>>? = null,
    /**
     * A global attribute: defined once and used by several types. In a type's effective attributes,
     * [typeId] is the type that uses it; on its own definition, [TypeId.GLOBAL].
     */
    val global: Boolean = false,
)

/** Settings that can change after an attribute is defined. Name, data type, multi-valued, enumeration and dimension cannot. */
data class AttributeSettings(
    val labels: Labels,
    val description: String? = null,
    val required: Boolean = false,
    val immutable: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val minValue: BigDecimal? = null,
    val maxValue: BigDecimal? = null,
    val displayUnit: String? = null,
    val defaultValue: Any? = null,
    val sortOrder: Int = 0,
    val active: Boolean = true,
    val dependsOn: String? = null,
    val valueFilter: Map<String, List<String>>? = null,
)

data class DefineAttribute(
    val typeId: TypeId,
    val name: String,
    val dataType: DataType,
    val multiValued: Boolean = false,
    val enumerationId: EnumerationId? = null,
    val dimension: String? = null,
    val settings: AttributeSettings,
)

data class Enumeration(
    val id: EnumerationId,
    val name: String,
    val labels: Labels,
    val system: Boolean,
    val values: List<EnumerationValue>,
)

data class EnumerationValue(
    val key: String,
    val labels: Labels,
    val active: Boolean = true,
)

data class UnitOfMeasure(
    val code: String,
    val dimension: String,
    val labels: Labels,
    /** value in the base unit = value × factor + offset */
    val factor: BigDecimal,
    val offset: BigDecimal,
    /** Shipped out of the box. */
    val system: Boolean = true,
)

/**
 * Attribute definitions (roadmap 1.1, 1.3). Defining and changing attributes is for site
 * administrators. Reading is open to everyone, because forms are rendered from definitions.
 *
 * Error codes: `ADMIN_REQUIRED`, `TYPE_NOT_FOUND`, `ATTRIBUTE_NOT_FOUND`, `INVALID_ATTRIBUTE_DEFINITION`,
 * `DUPLICATE_ATTRIBUTE_NAME`.
 */
interface AttributeService {
    /** All attributes of [typeId], including inherited ones: ancestors first, then by sort order. */
    fun effective(
        actor: Actor,
        typeId: TypeId,
        includeInactive: Boolean = false,
    ): List<AttributeDefinition>

    fun define(
        actor: Actor,
        command: DefineAttribute,
    ): AttributeDefinition

    fun update(
        actor: Actor,
        id: AttributeId,
        settings: AttributeSettings,
    ): AttributeDefinition

    /** Global attributes with the types that use them. */
    fun globals(actor: Actor): List<GlobalAttribute>

    /** Defines a global attribute ([DefineAttribute.typeId] is ignored). Site administrators only. */
    fun defineGlobal(
        actor: Actor,
        command: DefineAttribute,
    ): AttributeDefinition

    /**
     * Adds a global attribute to a type (and so its subtypes). Its name must be free along the type's
     * hierarchy (`DUPLICATE_ATTRIBUTE_NAME`). Site administrators only.
     */
    fun attachGlobal(
        actor: Actor,
        id: AttributeId,
        typeId: TypeId,
    )

    /** Values already stored stay with the objects, shown as values without a definition. */
    fun detachGlobal(
        actor: Actor,
        id: AttributeId,
        typeId: TypeId,
    )
}

data class GlobalAttribute(
    val definition: AttributeDefinition,
    /** Types that use it directly (their subtypes inherit it). */
    val usedBy: List<TypeId>,
)

/**
 * Validates attribute values against a type's definitions, applies defaults and normalizes
 * values (e.g. timestamps to UTC). Every write of attribute values goes through it.
 */
interface AttributeValidator {
    /**
     * @param previous the values before this change (for `immutable` and for keeping values of
     *   deactivated attributes), or null when creating an object.
     * @return the normalized values to store.
     * @throws com.plm.platform.error.ValidationException `INVALID_ATTRIBUTES`, with
     *   `details.errors = [{attribute, code, message}]`.
     */
    fun validate(
        actor: Actor,
        typeId: TypeId,
        values: Map<String, Any?>,
        previous: Map<String, Any?>?,
    ): Map<String, Any?>
}

/**
 * Enumerations (legal value lists) and units of measure (roadmap 1.2). Values are never removed,
 * only deactivated, so existing data stays valid and readable.
 *
 * Error codes: `ADMIN_REQUIRED`, `ENUMERATION_NOT_FOUND`, `INVALID_ENUMERATION`, `DUPLICATE_ENUMERATION_NAME`,
 * `ENUMERATION_VALUE_REMOVED`.
 */
interface EnumerationService {
    fun list(actor: Actor): List<Enumeration>

    fun get(
        actor: Actor,
        id: EnumerationId,
    ): Enumeration

    fun create(
        actor: Actor,
        name: String,
        labels: Labels,
        values: List<EnumerationValue>,
    ): Enumeration

    /** Replaces labels and the ordered value list. Every existing key must still be present (deactivate instead of removing). */
    fun update(
        actor: Actor,
        id: EnumerationId,
        labels: Labels,
        values: List<EnumerationValue>,
    ): Enumeration

    fun units(actor: Actor): List<UnitOfMeasure>

    /**
     * Adds a unit (site administrators). In an existing dimension it converts to that dimension's base
     * unit with [UnitOfMeasure.factor] and [UnitOfMeasure.offset]; the first unit of a new dimension is
     * its base unit (factor 1, offset 0). Units can't be changed or removed once stored values may use
     * them. `INVALID_UNIT`, `DUPLICATE_UNIT`.
     */
    fun createUnit(
        actor: Actor,
        unit: UnitOfMeasure,
    ): UnitOfMeasure
}
