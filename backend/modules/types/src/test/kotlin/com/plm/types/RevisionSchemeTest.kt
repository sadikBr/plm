package com.plm.types

import com.plm.types.api.RevisionScheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RevisionSchemeTest {
    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource(
        "ALPHABETIC_ASME,A,B",
        "ALPHABETIC_ASME,H,J",
        "ALPHABETIC_ASME,N,P",
        "ALPHABETIC_ASME,R,T",
        "ALPHABETIC_ASME,W,Y",
        "ALPHABETIC_ASME,Y,AA",
        "ALPHABETIC_ASME,AY,BA",
        "ALPHABETIC_ASME,YY,AAA",
        "ALPHABETIC,H,I",
        "ALPHABETIC,Z,AA",
        "ALPHABETIC,AZ,BA",
        "NUMERIC_FROM_1,1,2",
        "NUMERIC_FROM_1,9,10",
        "NUMERIC_FROM_0,0,1",
        "NUMERIC_FROM_0,99,100",
    )
    fun `advances to the next revision`(
        scheme: RevisionScheme,
        current: String,
        expected: String,
    ) {
        assertThat(scheme.next(current)).isEqualTo(expected)
    }

    @Test
    fun `first revisions`() {
        assertThat(RevisionScheme.entries.associateWith { it.first }).isEqualTo(
            mapOf(
                RevisionScheme.ALPHABETIC_ASME to "A",
                RevisionScheme.ALPHABETIC to "A",
                RevisionScheme.NUMERIC_FROM_1 to "1",
                RevisionScheme.NUMERIC_FROM_0 to "0",
            ),
        )
    }

    @Test
    fun `rejects revisions from another scheme`() {
        listOf("I", "O", "Q", "S", "X", "Z", "a", "A1", "").forEach {
            assertThatThrownBy {
                RevisionScheme.ALPHABETIC_ASME.next(
                    it,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf("A", "01", "-1", "").forEach {
            assertThatThrownBy {
                RevisionScheme.NUMERIC_FROM_1.next(
                    it,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `ASME letters never produce a confusable letter`() {
        var rev = RevisionScheme.ALPHABETIC_ASME.first
        repeat(5_000) {
            rev = RevisionScheme.ALPHABETIC_ASME.next(rev)
            assertThat(RevisionScheme.ALPHABETIC_ASME.isValid(rev)).isTrue()
        }
    }
}
