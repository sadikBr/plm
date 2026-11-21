package com.plm.variants

import com.plm.platform.error.ValidationException
import com.plm.variants.domain.Condition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Line conditions: parsing and evaluation (roadmap 7.7). */
internal class ConditionTest {
    private val ht = mapOf("MOTOR" to "HT", "BATTERY" to "L", "COLOR" to "BLUE")
    private val std = mapOf("MOTOR" to "STD", "BATTERY" to "S")

    private fun holds(
        text: String,
        selection: Map<String, String>,
    ) = Condition.parse(text).holds(selection)

    @Test
    fun `conditions read like they're written`() {
        assertThat(holds("MOTOR=HT", ht)).isTrue()
        assertThat(holds("motor = ht", std)).isFalse()
        assertThat(holds("COLOR in (SAND, BLUE)", ht)).isTrue()
        // An optional option left out matches nothing.
        assertThat(holds("COLOR in (SAND, BLUE)", std)).isFalse()
        assertThat(holds("not COLOR=BLUE", std)).isTrue()
        // not, then and, then or.
        assertThat(holds("MOTOR=STD or MOTOR=HT and BATTERY=S", ht)).isFalse()
        assertThat(holds("(MOTOR=STD or MOTOR=HT) and BATTERY=L", ht)).isTrue()
        assertThat(holds("not (MOTOR=HT and BATTERY=L)", ht)).isFalse()
        assertThat(
            Condition.parse("MOTOR=HT and not COLOR in (RED, BLUE)").options(),
        ).containsExactlyInAnyOrder("MOTOR", "COLOR")
    }

    @Test
    fun `nonsense is refused, saying where`() {
        assertThatThrownBy {
            Condition.parse("MOTOR=")
        }.isInstanceOf(ValidationException::class.java).hasMessageContaining("ends too soon")
        assertThatThrownBy { Condition.parse("MOTOR HT") }.hasMessageContaining("Expected '='")
        assertThatThrownBy { Condition.parse("(MOTOR=HT") }.hasMessageContaining("ends too soon")
        assertThatThrownBy { Condition.parse("MOTOR=HT)") }.hasMessageContaining("Unexpected ')'")
        assertThatThrownBy { Condition.parse("MOTOR=HT & X=Y") }.hasMessageContaining("Can't read '&")
        assertThatThrownBy { Condition.parse("and=HT") }.hasMessageContaining("Expected a code")
    }
}
