package com.plm.versioning.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.expression.ExpressionException
import com.plm.platform.expression.ExpressionKind
import com.plm.platform.expression.Expressions
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeValidator
import com.plm.types.api.DataType
import com.plm.types.api.EnumerationService
import com.plm.types.api.TypeId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

private val log = LoggerFactory.getLogger(Calculations::class.java)

/**
 * Calculated attributes (roadmap 2.2): their formulas run whenever an object is created, its
 * working copy edited, or checked in. Users can't set them (values sent for them are ignored).
 * A formula may use another's result, whatever their order. A formula that
 * fails (a missing value, a division by zero) leaves the attribute empty, like a spreadsheet cell.
 */
@Component
internal class Calculations(
    private val attributes: AttributeService,
    private val validator: AttributeValidator,
    private val enumerations: EnumerationService,
    private val models: ObjectModels,
) {
    fun formulas(
        actor: Actor,
        typeId: TypeId,
    ): List<AttributeDefinition> = attributes.effective(actor, typeId).filter { it.formula != null }

    /** What the user may set: [values] without calculated attributes. */
    fun userValues(
        actor: Actor,
        typeId: TypeId,
        values: Map<String, Any?>,
    ): Map<String, Any?> {
        val calculated = formulas(actor, typeId).map { it.name }.toSet()
        return if (calculated.isEmpty()) values else values - calculated
    }

    /** [facts] with its calculated attributes (re)computed and validated like any value. */
    fun apply(
        actor: Actor,
        facts: ObjectFacts,
        previous: Map<String, Any?>?,
    ): Map<String, Any?> {
        val formulas = formulas(actor, facts.typeId)
        if (formulas.isEmpty()) return facts.attributes
        val values = (facts.attributes - formulas.map { it.name }.toSet()).toMutableMap()
        val model = models.build(actor, facts.copy(attributes = values))

        @Suppress("UNCHECKED_CAST")
        val obj = model.getValue("object") as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val known = obj["attributes"] as Map<String, Any?>
        // Formulas may use each other's results: run them all until nothing changes (at most once
        // per formula more, which settles any chain without cycles; a cycle just stops there).
        for (pass in 0..formulas.size) {
            var changed = false
            for (def in formulas) {
                val current = model + ("object" to obj + ("attributes" to known + values))
                val result =
                    try {
                        coerce(Expressions.evaluate(def.formula!!, ExpressionKind.VALUE, current), def, actor)
                    } catch (e: ExpressionException) {
                        log.debug("Formula of {} gave no value: {}", def.name, e.message)
                        null
                    }
                if (values[def.name] != result) changed = true
                if (result == null) values.remove(def.name) else values[def.name] = result
            }
            if (!changed) break
        }
        return try {
            validator.validate(actor, facts.typeId, values, previous)
        } catch (e: ValidationException) {
            // A result that doesn't fit its attribute (too long, out of range...) leaves it empty;
            // anything else is the user's to fix.
            @Suppress("UNCHECKED_CAST")
            val failing = (e.details["errors"] as? List<Map<String, Any?>>).orEmpty().map { it["attribute"] }.toSet()
            val calculated = formulas.map { it.name }.filter { it in failing }
            if (calculated.isEmpty()) throw e
            validator.validate(actor, facts.typeId, values - calculated.toSet(), previous)
        }
    }

    /** Fits a formula's result to its attribute's data type; null when it can't. */
    private fun coerce(
        value: Any?,
        def: AttributeDefinition,
        actor: Actor,
    ): Any? {
        if (value == null) return null
        return when (def.dataType) {
            DataType.INTEGER ->
                when (value) {
                    is Long -> value
                    is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toLong()
                    else -> null
                }
            DataType.REAL -> (value as? Number)?.toDouble()
            DataType.BOOLEAN -> value as? Boolean
            DataType.QUANTITY ->
                when (value) {
                    is Map<*, *> -> value
                    is Number -> mapOf("value" to value.toDouble(), "unit" to (def.displayUnit ?: baseUnit(actor, def)))
                    else -> null
                }
            else -> if (value is Map<*, *> || value is List<*>) null else value.toString()
        }
    }

    private fun baseUnit(
        actor: Actor,
        def: AttributeDefinition,
    ) = enumerations
        .units(actor)
        .firstOrNull {
            it.dimension == def.dimension &&
                it.factor.compareTo(BigDecimal.ONE) == 0 &&
                it.offset.signum() == 0
        }?.code
}
