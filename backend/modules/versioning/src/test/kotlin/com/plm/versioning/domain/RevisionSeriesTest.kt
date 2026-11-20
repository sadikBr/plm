package com.plm.versioning.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class RevisionSeriesTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("A,B", "H,J", "N,P", "P,R", "R,T", "W,Y", "Y,AA", "AA,AB", "AY,BA", "YY,AAA", "BYY,CAA")
    fun `advances to the next revision`(
        current: String,
        expected: String,
    ) {
        assertThat(RevisionSeries.next(current)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "I", "O", "Q", "S", "X", "Z", "a", "A1"])
    fun `rejects invalid revisions`(revision: String) {
        assertThatThrownBy { RevisionSeries.next(revision) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @org.junit.jupiter.api.Test
    fun `never produces a confusable letter over a long run`() {
        var rev = RevisionSeries.first
        repeat(5_000) {
            rev = RevisionSeries.next(rev)
            assertThat(RevisionSeries.isValid(rev)).isTrue()
        }
    }
}
