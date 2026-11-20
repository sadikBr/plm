package com.plm.types.domain

import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeId
import com.plm.types.api.DataType
import com.plm.types.api.Enumeration
import com.plm.types.api.EnumerationId
import com.plm.types.api.EnumerationValue
import com.plm.types.api.TypeId
import com.plm.types.api.UnitOfMeasure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class AttributeRulesTest {
    private val sourceEnum =
        Enumeration(
            EnumerationId(UUID.randomUUID()),
            "partSource",
            mapOf("en" to "Source"),
            true,
            listOf(
                EnumerationValue("make", mapOf("en" to "Make")),
                EnumerationValue("buy", mapOf("en" to "Buy")),
                EnumerationValue("legacy", mapOf("en" to "Legacy"), active = false),
            ),
        )
    private val units =
        listOf(
            UnitOfMeasure("kg", "MASS", mapOf("en" to "kilogram"), BigDecimal.ONE, BigDecimal.ZERO),
            UnitOfMeasure("g", "MASS", mapOf("en" to "gram"), BigDecimal("0.001"), BigDecimal.ZERO),
            UnitOfMeasure("m", "LENGTH", mapOf("en" to "metre"), BigDecimal.ONE, BigDecimal.ZERO),
            UnitOfMeasure("K", "TEMPERATURE", mapOf("en" to "kelvin"), BigDecimal.ONE, BigDecimal.ZERO),
            UnitOfMeasure("degC", "TEMPERATURE", mapOf("en" to "degree Celsius"), BigDecimal.ONE, BigDecimal("273.15")),
        ).associateBy { it.code }

    private fun def(
        name: String,
        type: DataType,
        build: AttributeDefinition.() -> AttributeDefinition = { this },
    ) = AttributeDefinition(
        AttributeId(UUID.randomUUID()),
        TypeId(UUID.randomUUID()),
        name,
        mapOf(
            "en" to name.replaceFirstChar { it.uppercase() },
        ),
        null,
        type,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        if (type == DataType.ENUMERATION) sourceEnum.id else null,
        when (type) {
            DataType.QUANTITY -> "MASS"
            else -> null
        },
        null,
        null,
        0,
        true,
        false,
    ).build()

    private fun rules(vararg defs: AttributeDefinition) =
        AttributeRules(
            defs.toList(),
            mapOf(
                sourceEnum.id to sourceEnum,
            ),
            units,
        )

    private fun codes(result: ValidationResult) = result.errors.associate { it.attribute to it.code }

    @Nested
    inner class Normalization {
        @Test
        fun `each data type is normalized to one canonical form`() {
            val result =
                rules(
                    def("count", DataType.INTEGER),
                    def("ratio", DataType.REAL),
                    def("mass", DataType.QUANTITY),
                    def("flag", DataType.BOOLEAN),
                    def("due", DataType.DATE),
                    def("at", DataType.TIMESTAMP),
                    def("link", DataType.URL),
                    def("source", DataType.ENUMERATION),
                    def("note", DataType.TEXT),
                ).validate(
                    mapOf(
                        "count" to 7.0,
                        "ratio" to 3,
                        "mass" to mapOf("value" to 12, "unit" to "g"),
                        "flag" to true,
                        "due" to "2026-11-20",
                        "at" to "2026-11-20T12:00:00+02:00",
                        "link" to "https://example.com/spec",
                        "source" to "buy",
                        "note" to "multi\nline",
                    ),
                    null,
                )

            assertThat(result.errors).isEmpty()
            assertThat(result.values).containsExactly(
                org.assertj.core.api.Assertions
                    .entry("count", 7L),
                org.assertj.core.api.Assertions
                    .entry("ratio", 3.0),
                org.assertj.core.api.Assertions
                    .entry("mass", mapOf("value" to 12.0, "unit" to "g")),
                org.assertj.core.api.Assertions
                    .entry("flag", true),
                org.assertj.core.api.Assertions
                    .entry("due", "2026-11-20"),
                org.assertj.core.api.Assertions
                    .entry("at", "2026-11-20T10:00:00Z"),
                org.assertj.core.api.Assertions
                    .entry("link", "https://example.com/spec"),
                org.assertj.core.api.Assertions
                    .entry("source", "buy"),
                org.assertj.core.api.Assertions
                    .entry("note", "multi\nline"),
            )
        }

        @Test
        fun `absent values get defaults, blanks and explicit nulls clear`() {
            val r =
                rules(
                    def("source", DataType.ENUMERATION) { copy(defaultValue = "make") },
                    def("material", DataType.STRING) { copy(defaultValue = "steel") },
                    def("finish", DataType.STRING) { copy(defaultValue = "raw") },
                )

            val result = r.validate(mapOf("material" to "  ", "finish" to null), null)

            assertThat(result.values).isEqualTo(mapOf("source" to "make"))
        }

        @Test
        fun `multi-valued attributes take lists and validate each item`() {
            val tags = def("tags", DataType.STRING) { copy(multiValued = true, maxLength = 5) }

            assertThat(rules(tags).validate(mapOf("tags" to listOf("a", "", "b")), null).values).isEqualTo(
                mapOf(
                    "tags" to listOf("a", "b"),
                ),
            )
            assertThat(codes(rules(tags).validate(mapOf("tags" to "a"), null))).isEqualTo(mapOf("tags" to "WRONG_TYPE"))
            val tooLong = rules(tags).validate(mapOf("tags" to listOf("ok", "far too long")), null)
            assertThat(tooLong.errors.single().message).endsWith("(item 2)")
        }
    }

    @Nested
    inner class Constraints {
        @Test
        fun `unknown attributes and wrong types are rejected`() {
            val result =
                rules(def("count", DataType.INTEGER), def("flag", DataType.BOOLEAN), def("mass", DataType.QUANTITY))
                    .validate(mapOf("count" to 1.5, "flag" to "yes", "mass" to 3, "colour" to "red"), null)

            assertThat(codes(result)).isEqualTo(
                mapOf(
                    "colour" to "UNKNOWN_ATTRIBUTE",
                    "count" to "WRONG_TYPE",
                    "flag" to "WRONG_TYPE",
                    "mass" to "WRONG_TYPE",
                ),
            )
        }

        @Test
        fun `required, length, pattern and range`() {
            val result =
                rules(
                    def("name", DataType.STRING) { copy(required = true) },
                    def("code", DataType.STRING) { copy(pattern = "[A-Z]{3}-\\d+", minLength = 5) },
                    def("short", DataType.STRING) { copy(maxLength = 3) },
                    def("count", DataType.INTEGER) { copy(minValue = BigDecimal.ONE, maxValue = BigDecimal.TEN) },
                ).validate(mapOf("code" to "abc", "short" to "toolong", "count" to 11), null)

            assertThat(codes(result)).isEqualTo(
                mapOf("name" to "REQUIRED", "code" to "TOO_SHORT", "short" to "TOO_LONG", "count" to "OUT_OF_RANGE"),
            )
        }

        @Test
        fun `quantity ranges compare in base units, across units and offsets`() {
            val mass = def("mass", DataType.QUANTITY) { copy(maxValue = BigDecimal("2")) } // 2 kg
            assertThat(
                rules(mass).validate(mapOf("mass" to mapOf("value" to 1999, "unit" to "g")), null).errors,
            ).isEmpty()
            assertThat(codes(rules(mass).validate(mapOf("mass" to mapOf("value" to 2001, "unit" to "g")), null)))
                .isEqualTo(mapOf("mass" to "OUT_OF_RANGE"))

            val temp =
                def("maxTemp", DataType.QUANTITY) { copy(dimension = "TEMPERATURE", minValue = BigDecimal("273.15")) }
            assertThat(
                rules(temp).validate(mapOf("maxTemp" to mapOf("value" to 0, "unit" to "degC")), null).errors,
            ).isEmpty()
            assertThat(codes(rules(temp).validate(mapOf("maxTemp" to mapOf("value" to -1, "unit" to "degC")), null)))
                .isEqualTo(mapOf("maxTemp" to "OUT_OF_RANGE"))
        }

        @Test
        fun `quantity units must exist and match the dimension`() {
            val mass = def("mass", DataType.QUANTITY)

            assertThat(codes(rules(mass).validate(mapOf("mass" to mapOf("value" to 1, "unit" to "m")), null)))
                .isEqualTo(mapOf("mass" to "WRONG_DIMENSION"))
            assertThat(codes(rules(mass).validate(mapOf("mass" to mapOf("value" to 1, "unit" to "stone")), null)))
                .isEqualTo(mapOf("mass" to "UNKNOWN_UNIT"))
        }

        @Test
        fun `dates, timestamps and urls must parse`() {
            val result =
                rules(def("due", DataType.DATE), def("at", DataType.TIMESTAMP), def("link", DataType.URL))
                    .validate(mapOf("due" to "20/11/2026", "at" to "yesterday", "link" to "ftp://x"), null)

            assertThat(codes(result)).isEqualTo(
                mapOf(
                    "due" to "WRONG_TYPE",
                    "at" to "WRONG_TYPE",
                    "link" to "WRONG_TYPE",
                ),
            )
        }
    }

    @Nested
    inner class Changes {
        @Test
        fun `immutable values cannot change once set`() {
            val serial = def("serial", DataType.STRING) { copy(immutable = true) }

            assertThat(rules(serial).validate(mapOf("serial" to "S1"), null).errors).isEmpty()
            assertThat(rules(serial).validate(mapOf("serial" to "S1"), mapOf("serial" to "S1")).errors).isEmpty()
            assertThat(codes(rules(serial).validate(mapOf("serial" to "S2"), mapOf("serial" to "S1"))))
                .isEqualTo(mapOf("serial" to "IMMUTABLE"))
            // Objects created before the attribute existed can set it once.
            assertThat(rules(serial).validate(mapOf("serial" to "S9"), emptyMap()).errors).isEmpty()
        }

        @Test
        fun `stored numbers compare equal to their normalized form`() {
            val count = def("count", DataType.INTEGER) { copy(immutable = true) }

            // JSON storage gives back an Int; the new value normalizes to Long.
            assertThat(rules(count).validate(mapOf("count" to 5L), mapOf("count" to 5)).errors).isEmpty()
        }

        @Test
        fun `inactive enumeration values are kept but can't be newly chosen`() {
            val source = def("source", DataType.ENUMERATION)

            assertThat(codes(rules(source).validate(mapOf("source" to "legacy"), null))).isEqualTo(
                mapOf(
                    "source" to "INACTIVE_VALUE",
                ),
            )
            assertThat(
                rules(source).validate(mapOf("source" to "legacy"), mapOf("source" to "legacy")).errors,
            ).isEmpty()
            assertThat(codes(rules(source).validate(mapOf("source" to "plastic"), null))).isEqualTo(
                mapOf(
                    "source" to "UNKNOWN_VALUE",
                ),
            )
        }

        @Test
        fun `deactivated attributes keep their value and refuse new ones`() {
            val legacy = def("legacyCode", DataType.STRING) { copy(active = false) }

            assertThat(rules(legacy).validate(emptyMap(), mapOf("legacyCode" to "X1")).values).isEqualTo(
                mapOf(
                    "legacyCode" to "X1",
                ),
            )
            assertThat(
                rules(legacy).validate(mapOf("legacyCode" to "X1"), mapOf("legacyCode" to "X1")).errors,
            ).isEmpty()
            assertThat(codes(rules(legacy).validate(mapOf("legacyCode" to "X2"), mapOf("legacyCode" to "X1"))))
                .isEqualTo(mapOf("legacyCode" to "INACTIVE_ATTRIBUTE"))
            assertThat(codes(rules(legacy).validate(mapOf("legacyCode" to "new"), null)))
                .isEqualTo(mapOf("legacyCode" to "INACTIVE_ATTRIBUTE"))
        }
    }
}
