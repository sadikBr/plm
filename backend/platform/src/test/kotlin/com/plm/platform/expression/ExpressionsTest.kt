package com.plm.platform.expression

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExpressionsTest {
    private val part =
        mapOf(
            "object" to
                mapOf(
                    "number" to "0000000042",
                    "type" to "Bolt",
                    "types" to listOf("Part", "Fastener", "Bolt"),
                    "state" to "RELEASED",
                    "attributes" to
                        mapOf(
                            "description" to null,
                            "source" to "make",
                            "length" to mapOf("value" to 30, "unit" to "mm"),
                            "quantity" to 4,
                            "unitCost" to 0.25,
                        ),
                ),
            "user" to mapOf("id" to "bob", "siteAdmin" to false, "permissions" to listOf("READ", "MODIFY")),
        )

    @Test
    fun `conditions see the object, its attributes and the user`() {
        assertThat(Expressions.test("object.state == 'RELEASED' && 'Part' in object.types", part)).isTrue()
        assertThat(Expressions.test("object.attributes.source == 'make'", part)).isTrue()
        assertThat(Expressions.test("object.attributes.description == null", part)).isTrue()
        assertThat(Expressions.test("object.attributes.length.value > 25", part)).isTrue()
        assertThat(Expressions.test("'MANAGE' in user.permissions", part)).isFalse()
        assertThat(Expressions.test("object.number.startsWith('00')", part)).isTrue()
    }

    @Test
    fun `formulas compute values, numbers mixing ints and doubles`() {
        assertThat(
            Expressions.evaluate(
                "double(object.attributes.quantity) * object.attributes.unitCost",
                ExpressionKind.VALUE,
                part,
            ),
        ).isEqualTo(1.0)
        assertThat(Expressions.evaluate("object.attributes.quantity + 1", ExpressionKind.VALUE, part)).isEqualTo(5L)
        assertThat(
            Expressions.evaluate("object.number + '-' + object.type", ExpressionKind.VALUE, part),
        ).isEqualTo("0000000042-Bolt")
    }

    @Test
    fun `problems are reported with where they are`() {
        assertThat(Expressions.check("object.state ==", ExpressionKind.CONDITION)).isNotEmpty()
        assertThat(Expressions.check("1 + 1", ExpressionKind.CONDITION).single().message).contains("bool")
        // Fields are only known at run time: a condition that isn't true/false fails then.
        assertThatThrownBy { Expressions.test("object.state", part) }.hasMessageContaining("true or false")
        assertThat(Expressions.check("nosuch.thing == 1", ExpressionKind.CONDITION).single().message).contains("nosuch")
        assertThat(Expressions.check("object.state == 'X'", ExpressionKind.CONDITION)).isEmpty()
        assertThat(
            Expressions.check(" ", ExpressionKind.CONDITION).single().message,
        ).isEqualTo("The expression is empty")
        assertThat(Expressions.check("1 == 1 ".repeat(400), ExpressionKind.CONDITION)).isNotEmpty()
    }

    @Test
    fun `run-time failures are exceptions, not crashes`() {
        assertThatThrownBy {
            Expressions.test("object.attributes.nosuch == 1", part)
        }.isInstanceOf(ExpressionException::class.java)
        assertThatThrownBy {
            Expressions.evaluate("1 / 0", ExpressionKind.VALUE, part)
        }.isInstanceOf(ExpressionException::class.java)
    }

    @Test
    fun `loops are bounded`() {
        val big = mapOf("object" to mapOf("items" to (1..5_000).toList()), "user" to emptyMap<String, Any>())
        assertThatThrownBy {
            Expressions.test(
                "object.items.all(i, i > 0)",
                big,
            )
        }.isInstanceOf(ExpressionException::class.java)
    }
}
