package com.plm.platform.id

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UuidV7Test {
    @Test
    fun `sets version 7 and RFC variant`() {
        val id = UuidV7.generate()

        assertThat(id.version()).isEqualTo(7)
        assertThat(id.variant()).isEqualTo(2)
    }

    @Test
    fun `embeds the clock's millisecond timestamp`() {
        val instant = Instant.parse("2026-11-20T12:00:00.123Z")
        val id = UuidV7.generate(Clock.fixed(instant, ZoneOffset.UTC))

        assertThat(id.mostSignificantBits ushr 16).isEqualTo(instant.toEpochMilli())
    }

    @Test
    fun `ids from later milliseconds sort after earlier ones`() {
        val t0 = Instant.parse("2026-11-20T12:00:00Z")
        val earlier = UuidV7.generate(Clock.fixed(t0, ZoneOffset.UTC))
        val later = UuidV7.generate(Clock.fixed(t0.plusMillis(1), ZoneOffset.UTC))

        assertThat(earlier.toString()).isLessThan(later.toString())
    }

    @Test
    fun `generates unique ids`() {
        val ids = (1..10_000).map { UuidV7.generate() }.toSet()

        assertThat(ids).hasSize(10_000)
    }
}
