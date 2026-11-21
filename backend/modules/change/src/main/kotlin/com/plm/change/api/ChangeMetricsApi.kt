package com.plm.change.api

import com.plm.platform.actor.Actor
import java.time.LocalDate
import java.util.UUID

data class ChangeStateCount(
    val kind: ChangeKind,
    val state: String,
    val count: Int,
)

/** How long open changes of a kind have been open, in buckets of days. */
data class ChangeAges(
    val kind: ChangeKind,
    val upTo7: Int,
    val upTo30: Int,
    val upTo90: Int,
    val over90: Int,
)

/** Days from creation to resolution, for changes of a kind resolved in the period. */
data class CycleTime(
    val kind: ChangeKind,
    val resolved: Int,
    val averageDays: Double?,
    val medianDays: Double?,
)

data class WeekFlow(
    /** The Monday the week starts. */
    val week: LocalDate,
    val opened: Int,
    val resolved: Int,
)

/** An open change that is the reader's: they own it, or a task of it is planned for them. */
data class MyChange(
    val id: UUID,
    val number: String,
    val name: String,
    val kind: ChangeKind,
    val state: String,
    val ageDays: Int,
    /** OWNER or ASSIGNEE. */
    val why: String,
    val due: LocalDate?,
)

data class ChangeMetrics(
    val open: Int,
    val byState: List<ChangeStateCount>,
    val ages: List<ChangeAges>,
    val cycleTimes: List<CycleTime>,
    /** The last [weeks] weeks, oldest first. */
    val flow: List<WeekFlow>,
    val mine: List<MyChange>,
    /** The period cycle times cover, in days. */
    val periodDays: Int,
)

/** Change dashboards (roadmap 5.12), over the changes the reader can read. */
interface ChangeMetricsService {
    fun metrics(
        actor: Actor,
        contextId: UUID? = null,
        periodDays: Int = 180,
        weeks: Int = 12,
    ): ChangeMetrics
}
