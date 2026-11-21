package com.plm.reports.domain

import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import com.plm.jobs.api.JobService
import com.plm.notifications.api.Mail
import com.plm.notifications.api.MailService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.reports.api.AlertWhen
import com.plm.reports.api.ReportResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = LoggerFactory.getLogger(ReportScheduler::class.java)

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class ReportScheduling

/** Queues a job for each scheduled report that's due, and moves it to its next run. */
@Component
internal class ReportScheduler(
    private val jdbc: JdbcClient,
    private val jobs: JobService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    @Scheduled(
        fixedDelayString = "\${plm.reports.interval:30s}",
        initialDelayString = "\${plm.reports.initial-delay:20s}",
    )
    fun tick() {
        try {
            fireDue()
        } catch (e: Exception) {
            log.warn("Scheduled reports failed: {}", e.toString())
        }
    }

    fun fireDue(): Int =
        tx.execute {
            val now = clock.instant()
            val due =
                jdbc
                    .sql(
                        """
                        select id, tenant_id, name, cron, time_zone from report
                        where cron is not null and next_run_at <= :now
                        order by next_run_at limit 50 for update skip locked
                        """.trimIndent(),
                    ).param("now", Timestamp.from(now))
                    .query { rs, _ ->
                        listOf(
                            rs.getObject("id", UUID::class.java),
                            TenantId(rs.getObject("tenant_id", UUID::class.java)),
                            rs.getString("name"),
                            rs.getString("cron"),
                            rs.getString("time_zone"),
                        )
                    }.list()
            for ((id, tenant, name, cron, zone) in due) {
                val system = Actor(tenant as TenantId, PrincipalId("system"), siteAdmin = true)
                jobs.enqueueSystem(system, ReportJob.TYPE, mapOf("reportId" to id.toString()), "Report: $name")
                val next = runCatching { nextRun(cron as String, zone as String, now) }.getOrNull()
                jdbc
                    .sql(
                        "update report set next_run_at = :next where id = :id",
                    ).param("id", id)
                    .param(
                        "next",
                        next?.let {
                            Timestamp.from(it)
                        },
                    ).update()
            }
            due.size
        }
}

/**
 * Runs a scheduled report as its owner and mails it to its recipients, unless its alert condition
 * says there's nothing worth sending this time.
 */
@Component
internal class ReportJob(
    private val reports: DefaultReportService,
    private val mail: MailService,
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @param:Value("\${plm.ui-url:http://localhost:5173}") private val publicUrl: String,
) : JobHandler {
    override val type = TYPE
    override val label = "Scheduled report"
    override val description = "Runs a report and mails it to its recipients"
    override val maxAttempts = 2

    override fun run(context: JobContext): Map<String, Any?> {
        val id = UUID.fromString(context.payload["reportId"]?.toString() ?: throw JobPermanentFailure("No reportId"))
        val (report, ownerAdmin) = reports.load(id) ?: throw JobPermanentFailure("The report was deleted")
        val owner = Actor(context.actor.tenant, PrincipalId(report.owner), siteAdmin = ownerAdmin)
        val result = reports.execute(owner, report)
        context.progress(1, 2, "${result.total} found")

        val previous =
            jdbc
                .sql("select last_ids from report where id = :id")
                .param("id", id)
                .query { rs, _ -> rs.getString("last_ids") }
                .optional()
                .orElse(null)
                ?.let { reportJson.readValue(it, Array<String>::class.java).toSet() }
        val ids = result.rows.map { it.id.toString() }
        val fresh = if (previous == null) ids else ids.filter { it !in previous }
        val s = report.settings
        val send =
            when (s.alert) {
                AlertWhen.ALWAYS -> true
                AlertWhen.ANY -> result.total > 0
                AlertWhen.MORE_THAN -> result.total > (s.threshold ?: 0)
                AlertWhen.NEW -> fresh.isNotEmpty() && previous != null
            }
        val people = s.recipients.filter { !it.contains('@') }.map(::PrincipalId)
        val addresses = s.recipients.filter { it.contains('@') } + mail.addresses(owner.tenant, people)
        tx.executeWithoutResult {
            if (send) {
                mail.queue(owner.tenant, message(result, fresh.size, addresses))
            }
            jdbc
                .sql(
                    "update report set last_run_at = :now, last_total = :total, last_ids = cast(:ids as jsonb) where id = :id",
                ).param("id", id)
                .param("now", Timestamp.from(clock.instant()))
                .param("total", result.total)
                .param("ids", reportJson.writeValueAsString(ids))
                .update()
            jdbc
                .sql(
                    "insert into report_run (id, report_id, ran_at, total, new_count, sent_to, note) values (:rid, :id, :now, :total, :new, :sent, :note)",
                ).param("rid", UuidV7.generate(clock))
                .param("id", id)
                .param("now", Timestamp.from(clock.instant()))
                .param("total", result.total)
                .param("new", if (previous == null) 0 else fresh.size)
                .param("sent", if (send) addresses.size else 0)
                .param("note", if (send) null else "Not sent: the alert condition wasn't met")
                .update()
        }
        context.progress(2, 2, if (send) "Sent to ${addresses.size}" else "Nothing to send")
        return mapOf("total" to result.total, "new" to fresh.size, "sent" to send, "recipients" to addresses.size)
    }

    private fun message(
        r: ReportResult,
        fresh: Int,
        to: List<String>,
    ): Mail {
        val s = r.report.settings
        val base = publicUrl.trimEnd('/')
        val headline =
            when (s.alert) {
                AlertWhen.NEW -> "$fresh new since the last run, ${r.total} in all"
                else -> "${r.total} found"
            }
        val ranAt =
            DateTimeFormatter
                .ofPattern(
                    "yyyy-MM-dd HH:mm z",
                ).format(r.ranAt.atZone(java.time.ZoneId.of(s.timeZone)))
        val lines = r.rows.take(50).joinToString("\n") { "${it.number}  ${it.name}  ${it.version}  ${state(it.state)}" }
        val html =
            buildString {
                append("<div style=\"font-family:Arial,sans-serif;font-size:14px\">")
                append("<h2 style=\"margin:0 0 4px\">${esc(s.name)}</h2>")
                append("<p style=\"color:#555;margin:0 0 12px\">$headline · run $ranAt</p>")
                append("<table style=\"border-collapse:collapse\"><tr>")
                listOf("Number", "Name", "Version", "State").forEach {
                    append("<th style=\"text-align:left;border-bottom:1px solid #ccc;padding:4px 10px\">$it</th>")
                }
                append("</tr>")
                for (row in r.rows.take(50)) {
                    append(
                        "<tr><td style=\"padding:3px 10px\"><a href=\"$base/objects/${row.id}\">${esc(
                            row.number,
                        )}</a></td>",
                    )
                    append(
                        "<td style=\"padding:3px 10px\">${esc(
                            row.name,
                        )}</td><td style=\"padding:3px 10px\">${esc(row.version)}</td>",
                    )
                    append("<td style=\"padding:3px 10px\">${esc(state(row.state))}</td></tr>")
                }
                append("</table>")
                if (r.total > 50) append("<p>… and ${r.total - 50} more.</p>")
                append("<p><a href=\"$base/reports/${r.report.id}\">Open the report</a></p></div>")
            }
        return Mail(
            to = to,
            subject = "${s.name}: $headline",
            text = "${s.name}\n$headline\n\n$lines\n\n$base/reports/${r.report.id}",
            html = html,
            origin = "report:${r.report.id}",
        )
    }

    /** IN_WORK → In Work */
    private fun state(key: String) =
        key.lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val TYPE = "reports.run"
    }
}
