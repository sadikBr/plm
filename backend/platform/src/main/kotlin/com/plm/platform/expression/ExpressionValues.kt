package com.plm.platform.expression

import com.google.protobuf.NullValue
import dev.cel.common.values.CelByteString
import java.math.BigDecimal

/** Converts between plain Kotlin values and the ones CEL works with (longs, doubles, NullValue). */
internal object ExpressionValues {
    fun toCel(value: Any?): Any =
        when (value) {
            null -> NullValue.NULL_VALUE
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is Float -> value.toDouble()
            is BigDecimal -> value.toDouble()
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to toCel(v) }
            is Iterable<*> -> value.map(::toCel)
            is Array<*> -> value.map(::toCel)
            is Enum<*> -> value.name
            is Long, is Double, is String, is Boolean -> value
            else -> value.toString()
        }

    fun fromCel(value: Any?): Any? =
        when (value) {
            null, is NullValue, is dev.cel.common.values.NullValue -> null
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to fromCel(v) }
            is List<*> -> value.map(::fromCel)
            is CelByteString -> value.toString()
            else -> value
        }
}
