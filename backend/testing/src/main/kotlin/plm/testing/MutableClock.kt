package plm.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A [Clock] tests can pin and advance. Starts at the current time, truncated to microseconds like Postgres. */
class MutableClock(
    @Volatile private var now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun set(instant: Instant) {
        now = instant
    }

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
