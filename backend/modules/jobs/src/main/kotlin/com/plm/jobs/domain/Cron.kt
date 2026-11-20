package com.plm.jobs.domain

import org.springframework.scheduling.support.CronExpression
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * Cron expressions as people write them: five fields (minute hour day month weekday), or a macro
 * (`@hourly`, `@daily`, `@weekly`, `@monthly`). Spring's own have seconds first; we add them.
 */
internal object Cron {
    fun parse(cron: String): CronExpression {
        val text = cron.trim()
        val fields = text.split(Regex("\\s+"))
        return when {
            text.startsWith("@") -> CronExpression.parse(text)
            fields.size == 5 -> CronExpression.parse("0 $text")
            else -> throw IllegalArgumentException("Use five fields: minute hour day month weekday (e.g. 0 2 * * *)")
        }
    }

    fun zone(timeZone: String): ZoneId =
        try {
            ZoneId.of(timeZone)
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("Unknown time zone $timeZone")
        }

    fun next(
        cron: String,
        timeZone: String,
        after: Instant,
    ): Instant? = parse(cron).next(after.atZone(zone(timeZone)))?.toInstant()
}
