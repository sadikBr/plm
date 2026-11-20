package com.plm.platform.id

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Generates RFC 9562 version-7 UUIDs: a 48-bit millisecond timestamp followed by random bits.
 *
 * Time-ordered IDs keep B-tree inserts append-mostly, which matters for tables that will hold
 * hundreds of millions of rows (iterations, links, audit entries).
 */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(clock: Clock = Clock.systemUTC()): UUID {
        val millis = clock.millis()
        val randA = random.nextInt(1 shl 12).toLong()
        val randB = random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL

        val msb = (millis shl 16) or (0x7L shl 12) or randA
        val lsb = randB or Long.MIN_VALUE // variant bits 10xx
        return UUID(msb, lsb)
    }
}
