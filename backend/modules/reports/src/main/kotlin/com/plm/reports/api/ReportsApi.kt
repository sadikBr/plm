package com.plm.reports.api

import com.plm.platform.actor.Actor
import com.plm.query.api.QueryDefinition
import com.plm.query.api.QueryResult
import com.plm.search.api.SearchQuery
import java.time.Instant
import java.util.UUID

enum class AlertWhen {
    /** Every scheduled run is sent. */
    ALWAYS,

    /** Only when something is found. */
    ANY,

    /** Only when more than [ReportSettings.threshold] are found. */
    MORE_THAN,

    /** Only when objects appear that the previous run didn't find. */
    NEW,
}

data class ReportSettings(
    val name: String,
    val description: String? = null,
    val query: SearchQuery = SearchQuery(),
    /** Table columns: `number`, `name`, `type`, `state`, … or `attr:<name>`. */
    val columns: List<String> = listOf("number", "name", "type", "version", "state", "modified"),
    /** What the chart counts by: `state`, `type`, `context`, `owner` or `attr:<name>`; null: no chart. */
    val groupBy: String? = "state",
    val shared: Boolean = false,
    /** Five-field cron (e.g. `0 7 * * 1-5`); null: on demand only. */
    val cron: String? = null,
    val timeZone: String = "UTC",
    /** People (ids) or e-mail addresses. */
    val recipients: List<String> = emptyList(),
    val alert: AlertWhen = AlertWhen.ALWAYS,
    val threshold: Int? = null,
    /**
     * A report builder query (objects, links, conditions, columns, totals): when set, the report
     * runs it instead of [query] and [columns]; [groupBy] is then `col:<index>` for its chart.
     */
    val definition: QueryDefinition? = null,
    /** Answers to the query's questions on scheduled runs (and the default when run by hand). */
    val answers: Map<String, Any?> = emptyMap(),
)

data class Report(
    val id: UUID,
    val owner: String,
    val settings: ReportSettings,
    val nextRunAt: Instant?,
    val lastRunAt: Instant?,
    val lastTotal: Int?,
    val updatedAt: Instant,
)

/** An object as a report shows it: everything its columns may need. */
data class ReportRow(
    val id: UUID,
    val number: String,
    val name: String,
    val typeId: UUID,
    val contextId: UUID,
    val state: String,
    val version: String,
    val owner: String,
    val checkedOutBy: String?,
    val modifiedAt: Instant,
    val createdAt: Instant,
    val attributes: Map<String, Any?>,
)

data class ReportGroup(
    val key: String,
    val count: Int,
)

data class ReportResult(
    val report: Report,
    val total: Int,
    /** More matched than a report reads (1000): the counts are of the first 1000. */
    val capped: Boolean,
    val rows: List<ReportRow>,
    val groups: List<ReportGroup>,
    val ranAt: Instant,
    /** A report builder query's result (then [rows] is empty). */
    val table: QueryResult? = null,
) {
    /** The objects found: for a query, those it starts from. */
    val ids: List<UUID>
        get() =
            table?.let { t ->
                val from =
                    report.settings.definition
                        ?.from
                        ?.alias
                t.rows.mapNotNull { it.objects[from] }.distinct()
            } ?: rows.map { it.id }
}

data class ReportRun(
    val id: UUID,
    val ranAt: Instant,
    val total: Int,
    val newCount: Int,
    val sentTo: Int,
    val note: String?,
)

/**
 * Reports (roadmap 3.8): a search, its columns and a grouping, saved by name; run on demand, or on
 * a schedule that mails the result (always, or as an alert when something, too much or something
 * new is found). They run with their owner's access. Error codes: `REPORT_NOT_FOUND`,
 * `INVALID_REPORT`, `DUPLICATE_REPORT`.
 */
interface ReportService {
    /** Your own and shared reports. */
    fun list(actor: Actor): List<Report>

    fun get(
        actor: Actor,
        id: UUID,
    ): Report

    fun create(
        actor: Actor,
        settings: ReportSettings,
    ): Report

    fun update(
        actor: Actor,
        id: UUID,
        settings: ReportSettings,
    ): Report

    fun delete(
        actor: Actor,
        id: UUID,
    )

    /** Runs it now, with the actor's own access; [answers] to its questions override the saved ones. */
    fun run(
        actor: Actor,
        id: UUID,
        answers: Map<String, Any?> = emptyMap(),
    ): ReportResult

    /** An unsaved report, to try settings out. */
    fun preview(
        actor: Actor,
        settings: ReportSettings,
        answers: Map<String, Any?> = emptyMap(),
    ): ReportResult

    fun runs(
        actor: Actor,
        id: UUID,
    ): List<ReportRun>
}

enum class WidgetKind {
    /** A report's counts, as a bar chart. */
    REPORT_CHART,

    /** A report's first rows. */
    REPORT_TABLE,

    /** A report's total, as one number. */
    REPORT_COUNT,

    /** What you have checked out. */
    CHECKED_OUT,

    /** Open changes by kind and state (roadmap 5.12). */
    CHANGES_OPEN,

    /** Your open changes (roadmap 5.12). */
    CHANGES_MINE,

    /** What changed lately, where you can read. */
    RECENT,

    /** Your favorites (roadmap 3.9). */
    FAVORITES,

    /** What you opened lately (roadmap 3.9). */
    RECENT_VISITS,

    /** Your latest notifications (roadmap 3.9). */
    NOTIFICATIONS,
}

data class Widget(
    val id: String,
    val kind: WidgetKind,
    val title: String? = null,
    val reportId: UUID? = null,
    /** 1 (half) or 2 (full width). */
    val width: Int = 1,
    val limit: Int = 10,
)

data class Dashboard(
    val id: UUID,
    val name: String,
    val owner: String,
    val shared: Boolean,
    val widgets: List<Widget>,
    val updatedAt: Instant,
)

data class DashboardSettings(
    val name: String,
    val widgets: List<Widget>,
    val shared: Boolean = false,
)

/**
 * Dashboards and home pages (roadmap 3.8): widgets over reports and your own work, arranged by
 * whoever owns the dashboard; anyone can make a visible dashboard their home page.
 * Error codes: `DASHBOARD_NOT_FOUND`, `INVALID_DASHBOARD`.
 */
interface DashboardService {
    fun list(actor: Actor): List<Dashboard>

    fun get(
        actor: Actor,
        id: UUID,
    ): Dashboard

    fun create(
        actor: Actor,
        settings: DashboardSettings,
    ): Dashboard

    fun update(
        actor: Actor,
        id: UUID,
        settings: DashboardSettings,
    ): Dashboard

    fun delete(
        actor: Actor,
        id: UUID,
    )

    /** The actor's home page: their chosen dashboard, or the default one. */
    fun home(actor: Actor): Dashboard

    fun setHome(
        actor: Actor,
        dashboardId: UUID?,
    ): Dashboard
}
