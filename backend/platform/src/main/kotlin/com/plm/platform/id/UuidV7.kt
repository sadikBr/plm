package com.plm.platform.id

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Generates RFC 9562 version-7 UUIDs: a 48-bit millisecond timestamp, a 12-bit counter, then
 * 62 random bits.
 *
 * IDs are strictly increasing within one JVM (RFC 9562 §6.2, method 1). When several IDs are
 * generated in the same millisecond, or the clock steps backwards, the counter advances instead.
 * Across JVMs, IDs are ordered by millisecond only. Time-ordered IDs keep B-tree inserts
 * append-mostly, which matters for tables that will hold hundreds of millions of rows.
 */
object UuidV7 {
    private const val COUNTER_BITS = 12
    private const val MAX_COUNTER = (1 shl COUNTER_BITS) - 1

    private val random = SecureRandom()
    private val lock = Any()
    private var lastMillis = -1L
    private var counter = 0

    fun generate(clock: Clock = Clock.systemUTC()): UUID {
        val (millis, seq) = next(clock.millis())
        val msb = (millis shl 16) or (0x7L shl 12) or seq.toLong()
        val lsb = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE // variant bits 10xx
        return UUID(msb, lsb)
    }

    private fun next(now: Long): Pair<Long, Int> =
        synchronized(lock) {
            if (now > lastMillis) {
                lastMillis = now
                // Start low in the counter space, leaving room to increment within the millisecond.
                counter = random.nextInt(1 shl (COUNTER_BITS - 2))
            } else if (counter < MAX_COUNTER) {
                counter++
            } else {
                // Counter exhausted: borrow the next millisecond.
                lastMillis++
                counter = 0
            }
            lastMillis to counter
        }
}
