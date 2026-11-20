package com.plm.types.domain

import com.plm.types.api.AttributeDefinition
import com.plm.types.api.DataType
import com.plm.types.api.Enumeration
import com.plm.types.api.EnumerationId
import com.plm.types.api.UnitOfMeasure
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal data class FieldError(
    val attribute: String,
    val code: String,
    val message: String,
)

internal data class ValidationResult(
    val values: Map<String, Any?>,
    val errors: List<FieldError>,
)

private const val DEFAULT_STRING_MAX = 1000
private const val DEFAULT_TEXT_MAX = 100_000
private const val URL_MAX = 2000

/**
 * The validation rules for attribute values, as a pure function of the definitions, enumerations
 * and units. No I/O, so every rule is unit-tested directly.
 *
 * - Unknown attribute names are rejected.
 * - Absent attributes get their default value. Blank strings and empty lists count as absent.
 * - Values are normalized to one canonical form (Long for INTEGER, Double for REAL,
 *   `{value, unit}` for QUANTITY, UTC ISO for TIMESTAMP), so comparisons and storage are stable.
 * - Deactivated attributes and enumeration values keep their existing values, but can't be newly set.
 */
internal class AttributeRules(
    private val definitions: List<AttributeDefinition>,
    private val enumerations: Map<EnumerationId, Enumeration>,
    private val units: Map<String, UnitOfMeasure>,
) {
    private class Invalid(
        val code: String,
        override val message: String,
    ) : RuntimeException(message, null, false, false)

    fun validate(
        values: Map<String, Any?>,
        previous: Map<String, Any?>?,
    ): ValidationResult {
        val byName = definitions.associateBy { it.name }
        val errors = mutableListOf<FieldError>()
        val out = linkedMapOf<String, Any?>()

        values.keys.filter { it !in byName }.sorted().forEach {
            errors += FieldError(it, "UNKNOWN_ATTRIBUTE", "'$it' is not an attribute of this type")
        }

        for (def in definitions) {
            val prevRaw = previous?.get(def.name)
            val prevNormalized = prevRaw?.let { quietly(def, it) }
            val prevKeys = enumKeys(prevNormalized)

            if (!def.active) {
                // Hidden from forms: keep whatever the object already has, refuse new values.
                val given = values[def.name]
                when {
                    def.name !in values || absent(given) || quietly(def, given!!) == prevNormalized -> {
                        if (prevRaw != null) out[def.name] = prevNormalized ?: prevRaw
                    }
                    else -> errors += FieldError(def.name, "INACTIVE_ATTRIBUTE", "${label(def)} can no longer be set")
                }
                continue
            }

            val raw = if (def.name in values) values[def.name] else def.defaultValue
            val normalized =
                if (absent(raw)) {
                    null
                } else {
                    try {
                        normalize(def, raw!!, prevKeys)
                    } catch (e: Invalid) {
                        errors += FieldError(def.name, e.code, e.message)
                        continue
                    }
                }

            if (normalized == null && def.required) {
                errors += FieldError(def.name, "REQUIRED", "${label(def)} is required")
                continue
            }
            // Immutable = fixed once it has a value (objects created before the attribute existed may set it once).
            if (def.immutable && prevNormalized != null && normalized != prevNormalized) {
                errors += FieldError(def.name, "IMMUTABLE", "${label(def)} cannot be changed after creation")
                continue
            }
            if (normalized != null) out[def.name] = normalized
        }
        // Dependent value lists: the parent's value decides which values are allowed.
        for (def in definitions) {
            val filter = def.valueFilter ?: continue
            val parentValue = out[def.dependsOn ?: continue] as? String ?: continue
            val allowed = filter[parentValue] ?: continue
            val chosen = (out[def.name] as? List<*>) ?: listOfNotNull(out[def.name])
            val refused = chosen.filter { it !in allowed }
            if (refused.isNotEmpty() && errors.none { it.attribute == def.name }) {
                val parent = byName[def.dependsOn]
                errors +=
                    FieldError(
                        def.name,
                        "NOT_ALLOWED_FOR_PARENT",
                        "${refused.joinToString()} is not allowed for ${label(
                            def,
                        )} when ${parent?.let(::label) ?: def.dependsOn} is $parentValue",
                    )
            }
        }
        return ValidationResult(out, errors)
    }

    /** Validates one value (or list, for multi-valued) against [def]. Used for default values. */
    fun check(
        def: AttributeDefinition,
        value: Any,
    ): FieldError? =
        try {
            normalize(def, value, emptySet())
            null
        } catch (e: Invalid) {
            FieldError(def.name, e.code, e.message)
        }

    private fun quietly(
        def: AttributeDefinition,
        value: Any,
    ): Any? =
        try {
            normalize(def, value, allowAnyEnumKey = true)
        } catch (_: Invalid) {
            value
        }

    private fun normalize(
        def: AttributeDefinition,
        value: Any,
        previousKeys: Set<String>,
    ): Any? = normalize(def, value, allowAnyEnumKey = false, previousKeys = previousKeys)

    private fun normalize(
        def: AttributeDefinition,
        value: Any,
        allowAnyEnumKey: Boolean,
        previousKeys: Set<String> = emptySet(),
    ): Any? {
        if (!def.multiValued) {
            if (value is List<*>) throw Invalid("WRONG_TYPE", "${label(def)} takes a single value, not a list")
            return one(def, value, allowAnyEnumKey, previousKeys)
        }
        if (value !is List<*>) throw Invalid("WRONG_TYPE", "${label(def)} takes a list of values")
        val items = value.filterNot { absent(it) }
        if (items.isEmpty()) return null
        return items.mapIndexed { i, item ->
            try {
                one(def, item!!, allowAnyEnumKey, previousKeys)
            } catch (e: Invalid) {
                throw Invalid(e.code, "${e.message} (item ${i + 1})")
            }
        }
    }

    private fun one(
        def: AttributeDefinition,
        value: Any,
        allowAnyEnumKey: Boolean,
        previousKeys: Set<String>,
    ): Any {
        val name = label(def)
        return when (def.dataType) {
            DataType.STRING, DataType.TEXT -> {
                val s = value as? String ?: throw Invalid("WRONG_TYPE", "$name must be text")
                val max = def.maxLength ?: if (def.dataType == DataType.STRING) DEFAULT_STRING_MAX else DEFAULT_TEXT_MAX
                if (s.length > max) throw Invalid("TOO_LONG", "$name must be at most $max characters")
                def.minLength?.let {
                    if (s.length <
                        it
                    ) {
                        throw Invalid("TOO_SHORT", "$name must be at least $it characters")
                    }
                }
                def.pattern?.let {
                    if (!Regex(
                            it,
                        ).matches(s)
                    ) {
                        throw Invalid("PATTERN_MISMATCH", "$name doesn't have the required format")
                    }
                }
                s
            }

            DataType.INTEGER -> {
                val n = number(value, name)
                val whole =
                    try {
                        n.stripTrailingZeros().also { if (it.scale() > 0) throw ArithmeticException() }.longValueExact()
                    } catch (_: ArithmeticException) {
                        throw Invalid("WRONG_TYPE", "$name must be a whole number")
                    }
                range(def, BigDecimal.valueOf(whole), name)
                whole
            }

            DataType.REAL -> {
                val n = number(value, name)
                range(def, n, name)
                n.toDouble()
            }

            DataType.QUANTITY -> {
                val map =
                    value as? Map<*, *>
                        ?: throw Invalid("WRONG_TYPE", "$name must be {\"value\": number, \"unit\": code}")
                val unitCode = map["unit"] as? String ?: throw Invalid("WRONG_TYPE", "$name needs a unit")
                val unit = units[unitCode] ?: throw Invalid("UNKNOWN_UNIT", "'$unitCode' is not a known unit")
                if (unit.dimension != def.dimension) {
                    throw Invalid(
                        "WRONG_DIMENSION",
                        "$name must be a ${def.dimension?.lowercase()}, not a ${unit.dimension.lowercase()}",
                    )
                }
                val amount = number(map["value"] ?: throw Invalid("WRONG_TYPE", "$name needs a value"), name)
                range(def, amount.multiply(unit.factor).add(unit.offset), name)
                mapOf("value" to amount.toDouble(), "unit" to unit.code)
            }

            DataType.BOOLEAN -> value as? Boolean ?: throw Invalid("WRONG_TYPE", "$name must be true or false")

            DataType.DATE -> {
                val s = value as? String ?: throw Invalid("WRONG_TYPE", "$name must be a date like 2026-11-20")
                try {
                    LocalDate.parse(s).toString()
                } catch (_: DateTimeParseException) {
                    throw Invalid("WRONG_TYPE", "$name must be a date like 2026-11-20")
                }
            }

            DataType.TIMESTAMP -> {
                val s =
                    value as? String
                        ?: throw Invalid("WRONG_TYPE", "$name must be a timestamp like 2026-11-20T10:15:30Z")
                try {
                    OffsetDateTime.parse(s).toInstant().toString()
                } catch (_: DateTimeParseException) {
                    try {
                        Instant.parse(s).toString()
                    } catch (_: DateTimeParseException) {
                        throw Invalid("WRONG_TYPE", "$name must be a timestamp like 2026-11-20T10:15:30Z")
                    }
                }
            }

            DataType.URL -> {
                val s = value as? String ?: throw Invalid("WRONG_TYPE", "$name must be a URL")
                if (s.length > (def.maxLength ?: URL_MAX)) throw Invalid("TOO_LONG", "$name is too long")
                val uri =
                    try {
                        URI(s)
                    } catch (_: Exception) {
                        null
                    }
                if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
                    throw Invalid("WRONG_TYPE", "$name must be an http(s) URL")
                }
                s
            }

            DataType.ENUMERATION -> {
                val key = value as? String ?: throw Invalid("WRONG_TYPE", "$name must be one of its listed values")
                val enumeration =
                    enumerations[def.enumerationId] ?: throw Invalid("UNKNOWN_VALUE", "$name has no value list")
                val entry =
                    enumeration.values.find { it.key == key }
                        ?: throw Invalid("UNKNOWN_VALUE", "'$key' is not a valid $name")
                if (!entry.active && !allowAnyEnumKey && key !in previousKeys) {
                    throw Invalid("INACTIVE_VALUE", "'$key' is no longer a valid $name")
                }
                key
            }
        }
    }

    private fun number(
        value: Any,
        name: String,
    ): BigDecimal =
        when (value) {
            is BigDecimal -> value
            is Int, is Long, is Short, is Byte -> BigDecimal.valueOf((value as Number).toLong())
            is java.math.BigInteger -> BigDecimal(value)
            is Double, is Float -> {
                val d = (value as Number).toDouble()
                if (!d.isFinite()) throw Invalid("WRONG_TYPE", "$name must be a finite number")
                BigDecimal.valueOf(d)
            }
            else -> throw Invalid("WRONG_TYPE", "$name must be a number")
        }

    private fun range(
        def: AttributeDefinition,
        n: BigDecimal,
        name: String,
    ) {
        def.minValue?.let {
            if (n <
                it
            ) {
                throw Invalid("OUT_OF_RANGE", "$name must be at least ${it.toPlainString()}${baseUnit(def)}")
            }
        }
        def.maxValue?.let {
            if (n >
                it
            ) {
                throw Invalid("OUT_OF_RANGE", "$name must be at most ${it.toPlainString()}${baseUnit(def)}")
            }
        }
    }

    private fun baseUnit(def: AttributeDefinition): String =
        def.dimension
            ?.let { dim ->
                units.values.firstOrNull {
                    it.dimension == dim &&
                        it.factor.compareTo(BigDecimal.ONE) == 0 &&
                        it.offset.signum() == 0
                }
            }?.let { " ${it.code}" } ?: ""

    private fun enumKeys(value: Any?): Set<String> =
        when (value) {
            is String -> setOf(value)
            is List<*> -> value.filterIsInstance<String>().toSet()
            else -> emptySet()
        }

    private fun absent(value: Any?): Boolean =
        value == null || (value is String && value.isBlank()) || (value is List<*> && value.all { absent(it) })

    private fun label(def: AttributeDefinition) = def.labels["en"] ?: def.name
}
