package com.plm.reports.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.query.api.QueryDefinition
import com.plm.query.api.QueryService
import com.plm.reports.api.AlertWhen
import com.plm.reports.api.Report
import com.plm.reports.api.ReportGroup
import com.plm.reports.api.ReportResult
import com.plm.reports.api.ReportRow
import com.plm.reports.api.ReportRun
import com.plm.reports.api.ReportService
import com.plm.reports.api.ReportSettings
import com.plm.search.api.SearchQuery
import com.plm.search.api.SearchService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal val reportJson: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

/** Next run of a five-field cron in [zone], after [after]. */
internal fun nextRun(
    cron: String,
    zone: String,
    after: Instant,
): Instant {
    val expression =
        try {
            CronExpression.parse(if (cron.trim().startsWith("@")) cron.trim() else "0 ${cron.trim()}")
        } catch (e: IllegalArgumentException) {
            throw ValidationException(
                "INVALID_REPORT",
                "The schedule isn't a valid cron expression: minute hour day month weekday",
            )
        }
    val z =
        try {
            ZoneId.of(zone)
        } catch (e: Exception) {
            throw ValidationException("INVALID_REPORT", "Unknown time zone $zone")
        }
    return expression.next(after.atZone(z))?.toInstant()
        ?: throw ValidationException("INVALID_REPORT", "The schedule never runs")
}

@Service
@Transactional
internal class DefaultReportService(
    private val jdbc: JdbcClient,
    private val search: SearchService,
    private val queries: QueryService,
    private val versioning: VersioningService,
    private val clock: Clock,
) : ReportService {
    private fun map(rs: ResultSet) =
        Report(
            id = rs.getObject("id", UUID::class.java),
            owner = rs.getString("owner"),
            settings =
                ReportSettings(
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                    query = reportJson.readValue(rs.getString("query"), SearchQuery::class.java),
                    columns = reportJson.readValue(rs.getString("columns"), Array<String>::class.java).toList(),
                    groupBy = rs.getString("group_by"),
                    shared = rs.getBoolean("shared"),
                    cron = rs.getString("cron"),
                    timeZone = rs.getString("time_zone"),
                    recipients = reportJson.readValue(rs.getString("recipients"), Array<String>::class.java).toList(),
                    alert = AlertWhen.valueOf(rs.getString("alert")),
                    threshold = rs.getObject("threshold") as Int?,
                    definition =
                        rs
                            .getString(
                                "definition",
                            )?.let { reportJson.readValue(it, QueryDefinition::class.java) },
                    answers =
                        reportJson
                            .readValue(
                                rs.getString("answers"),
                                Map::class.java,
                            ).mapKeys { it.key.toString() },
                ),
            nextRunAt = rs.getTimestamp("next_run_at")?.toInstant(),
            lastRunAt = rs.getTimestamp("last_run_at")?.toInstant(),
            lastTotal = rs.getObject("last_total") as Int?,
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )

    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<Report> =
        jdbc
            .sql(
                "select * from report where tenant_id = :tenant and (owner = :me or shared) order by owner = :me desc, lower(name)",
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .query { rs, _ -> map(rs) }
            .list()

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: UUID,
    ): Report =
        list(actor).firstOrNull { it.id == id } ?: throw NotFoundException("REPORT_NOT_FOUND", "There's no such report")

    /** For the scheduler: any report, whoever owns it. */
    fun load(id: UUID): Pair<Report, Boolean>? =
        jdbc
            .sql("select * from report where id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) to rs.getBoolean("owner_admin") }
            .optional()
            .orElse(null)

    override fun create(
        actor: Actor,
        settings: ReportSettings,
    ): Report = save(actor, null, settings)

    override fun update(
        actor: Actor,
        id: UUID,
        settings: ReportSettings,
    ): Report = save(actor, id, settings)

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        own(actor, id)
        jdbc.sql("delete from report where id = :id").param("id", id).update()
    }

    private fun own(
        actor: Actor,
        id: UUID,
    ): Report {
        val r = get(actor, id)
        if (r.owner != actor.principal.value) throw ForbiddenException("ACCESS_DENIED", "Only its owner can change it")
        return r
    }

    private fun save(
        actor: Actor,
        id: UUID?,
        s: ReportSettings,
    ): Report {
        val name = s.name.trim()
        if (name.isEmpty() || name.length > 100) throw ValidationException("INVALID_REPORT", "Give the report a name")
        if (s.definition != null) queries.validate(actor, s.definition)
        if (s.definition == null && (s.columns.isEmpty() || s.columns.size > 60)) {
            throw ValidationException("INVALID_REPORT", "A report shows 1 to 60 columns")
        }
        if (s.alert == AlertWhen.MORE_THAN &&
            s.threshold == null
        ) {
            throw ValidationException("INVALID_REPORT", "More than how many?")
        }
        val cron = s.cron?.trim()?.ifEmpty { null }
        val next = cron?.let { nextRun(it, s.timeZone, clock.instant()) }
        if (cron != null &&
            s.recipients.isEmpty()
        ) {
            throw ValidationException("INVALID_REPORT", "A scheduled report needs someone to send it to")
        }
        if (id != null) own(actor, id)
        val newId = id ?: UuidV7.generate(clock)
        val now = Timestamp.from(clock.instant())
        try {
            jdbc
                .sql(
                    """
                    insert into report (id, tenant_id, owner, owner_admin, name, description, query, columns, group_by, shared, cron,
                                        time_zone, next_run_at, recipients, alert, threshold, definition, answers, created_at, updated_at)
                    values (:id, :tenant, :owner, :admin, :name, :description, cast(:query as jsonb), cast(:columns as jsonb), :groupBy,
                            :shared, :cron, :tz, :next, cast(:recipients as jsonb), :alert, :threshold,
                            cast(:definition as jsonb), cast(:answers as jsonb), :now, :now)
                    on conflict (id) do update set owner_admin = excluded.owner_admin, name = excluded.name,
                        description = excluded.description, query = excluded.query, columns = excluded.columns,
                        group_by = excluded.group_by, shared = excluded.shared, cron = excluded.cron, time_zone = excluded.time_zone,
                        next_run_at = excluded.next_run_at, recipients = excluded.recipients, alert = excluded.alert,
                        threshold = excluded.threshold, definition = excluded.definition, answers = excluded.answers,
                        updated_at = excluded.updated_at
                    """.trimIndent(),
                ).param("id", newId)
                .param("tenant", actor.tenant.value)
                .param("owner", actor.principal.value)
                .param("admin", actor.siteAdmin)
                .param("name", name)
                .param("description", s.description?.trim()?.ifEmpty { null })
                .param("query", reportJson.writeValueAsString(s.query.copy(offset = 0)))
                .param("columns", reportJson.writeValueAsString(s.columns))
                .param("groupBy", s.groupBy?.ifBlank { null })
                .param("shared", s.shared)
                .param("cron", cron)
                .param("tz", s.timeZone)
                .param("next", next?.let { Timestamp.from(it) })
                .param(
                    "recipients",
                    reportJson.writeValueAsString(
                        s.recipients
                            .map {
                                it.trim()
                            }.filter { it.isNotEmpty() }
                            .distinct(),
                    ),
                ).param("alert", s.alert.name)
                .param("threshold", s.threshold)
                .param("definition", s.definition?.let { reportJson.writeValueAsString(it) })
                .param("answers", reportJson.writeValueAsString(s.answers))
                .param("now", now)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("DUPLICATE_REPORT", "You already have a report named $name")
        }
        return get(actor, newId)
    }

    override fun run(
        actor: Actor,
        id: UUID,
        answers: Map<String, Any?>,
    ): ReportResult = execute(actor, get(actor, id), answers)

    override fun preview(
        actor: Actor,
        settings: ReportSettings,
        answers: Map<String, Any?>,
    ): ReportResult =
        execute(
            actor,
            Report(UUID(0, 0), actor.principal.value, settings, null, null, null, clock.instant()),
            answers,
        )

    /** Runs [report] with [actor]'s access: its query, or its search then its rows and counts. */
    fun execute(
        actor: Actor,
        report: Report,
        answers: Map<String, Any?> = emptyMap(),
    ): ReportResult {
        report.settings.definition?.let { definition ->
            val table = queries.run(actor, definition, report.settings.answers + answers, 1000)
            return ReportResult(
                report,
                table.rows.size,
                table.capped,
                emptyList(),
                chart(report.settings, table),
                clock.instant(),
                table,
            )
        }
        val matches = search.matching(actor, report.settings.query.copy(offset = 0))
        val objects =
            matches.ids
                .chunked(500)
                .flatMap { ids -> versioning.list(actor, ObjectQuery(ids = ids.map(::MasterId), limit = 500)) }
                .associateBy { it.id.value }
        val rows = matches.ids.mapNotNull { objects[it] }.map(::row)
        val groupBy = report.settings.groupBy
        val groups =
            if (groupBy == null) {
                emptyList()
            } else {
                rows
                    .groupingBy { key(it, groupBy) }
                    .eachCount()
                    .map { ReportGroup(it.key, it.value) }
                    .sortedByDescending { it.count }
            }
        return ReportResult(report, rows.size, matches.capped, rows, groups, clock.instant())
    }

    /**
     * A query's chart: by the column `col:<k>` names (default the first). With totals, each row is a
     * bar and its first total the height; without, rows are counted by that column.
     */
    private fun chart(
        s: ReportSettings,
        t: com.plm.query.api.QueryResult,
    ): List<ReportGroup> {
        val by = s.groupBy ?: return emptyList()
        val k = by.removePrefix("col:").toIntOrNull()?.takeIf { it in t.columns.indices } ?: 0

        fun show(v: Any?) = v?.toString() ?: ""
        val total = t.columns.indexOfFirst { it.aggregate != null }
        return if (total >= 0) {
            t.rows.map { ReportGroup(show(it.values[k]), (it.values[total] as? Number)?.toInt() ?: 0) }
        } else {
            t.rows
                .groupingBy {
                    show(it.values[k])
                }.eachCount()
                .map { ReportGroup(it.key, it.value) }
                .sortedByDescending { it.count }
        }
    }

    private fun key(
        r: ReportRow,
        groupBy: String,
    ): String =
        when {
            groupBy == "state" -> r.state
            groupBy == "type" -> r.typeId.toString()
            groupBy == "context" -> r.contextId.toString()
            groupBy == "owner" -> r.owner
            groupBy.startsWith("attr:") ->
                r.attributes[groupBy.removePrefix("attr:")]?.let { v ->
                    if (v is Map<*, *> &&
                        v.containsKey("value")
                    ) {
                        "${v["value"]} ${v["unit"] ?: ""}".trim()
                    } else {
                        v.toString()
                    }
                }
                    ?: ""
            else -> ""
        }

    private fun row(o: VersionedObject) =
        ReportRow(
            id = o.id.value,
            number = o.number,
            name = o.name,
            typeId = o.typeId.value,
            contextId = o.contextId.value,
            state = o.latest.state,
            version = o.latest.version,
            owner = o.owner,
            checkedOutBy = o.checkedOutBy,
            modifiedAt = o.latest.checkedInAt ?: o.latest.createdAt,
            createdAt = o.createdAt,
            attributes = o.latest.attributes,
        )

    @Transactional(readOnly = true)
    override fun runs(
        actor: Actor,
        id: UUID,
    ): List<ReportRun> {
        get(actor, id)
        return jdbc
            .sql("select * from report_run where report_id = :id order by ran_at desc limit 20")
            .param("id", id)
            .query { rs, _ ->
                ReportRun(
                    rs.getObject("id", UUID::class.java),
                    rs.getTimestamp("ran_at").toInstant(),
                    rs.getInt("total"),
                    rs.getInt("new_count"),
                    rs.getInt("sent_to"),
                    rs.getString("note"),
                )
            }.list()
    }
}
