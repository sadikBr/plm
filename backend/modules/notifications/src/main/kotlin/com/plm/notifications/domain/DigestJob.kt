package com.plm.notifications.domain

import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.notifications.api.Mail
import com.plm.notifications.api.MailService
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * The daily digest (roadmap 3.9): one mail per person who chose it, listing the notifications not
 * mailed yet. Scheduled every morning (Admin → Jobs); running it again sends only what's new.
 */
@Component
internal class DigestJob(
    private val jdbc: JdbcClient,
    private val mail: MailService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @param:Value("\${plm.ui-url:http://localhost:5173}") private val uiUrl: String,
) : JobHandler {
    override val type = TYPE
    override val label = "Notification digest"
    override val description = "Mails each person who asked for a daily digest what's new for them"
    override val maxAttempts = 1

    private data class Row(
        val id: UUID,
        val principal: String,
        val title: String,
        val body: String?,
        val link: String?,
    )

    override fun run(context: JobContext): Map<String, Any?> {
        val tenant = context.actor.tenant
        val rows =
            jdbc
                .sql(
                    """
                    select n.id, n.principal_id, n.title, n.body, n.link from notification n
                    left join notification_setting s on s.tenant_id = n.tenant_id and s.principal_id = n.principal_id
                    where n.tenant_id = :tenant and n.mailed_at is null and n.read_at is null and coalesce(s.mail, 'DAILY') = 'DAILY'
                    order by n.principal_id, n.seq
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .query { rs, _ ->
                    Row(
                        rs.getObject("id", UUID::class.java),
                        rs.getString("principal_id"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("link"),
                    )
                }.list()
        val byPerson = rows.groupBy { it.principal }
        var sent = 0
        byPerson.entries.forEachIndexed { i, (principal, items) ->
            tx.executeWithoutResult {
                val to = mail.addresses(tenant, listOf(PrincipalId(principal)))
                if (to.isNotEmpty()) {
                    mail.queue(tenant, message(to, items))
                    sent++
                }
                markMailed(tenant, principal, items.map { it.id })
            }
            context.progress(i + 1, byPerson.size, "$sent sent")
        }
        return mapOf("people" to byPerson.size, "sent" to sent, "notifications" to rows.size)
    }

    private fun markMailed(
        tenant: TenantId,
        principal: String,
        ids: List<UUID>,
    ) {
        val now = Timestamp.from(clock.instant())
        jdbc
            .sql("update notification set mailed_at = :now where id = any(cast(:ids as uuid[]))")
            .param("now", now)
            .param("ids", ids.toTypedArray())
            .update()
        jdbc
            .sql(
                """
                insert into notification_setting (tenant_id, principal_id, mail, last_digest_at) values (:tenant, :me, 'DAILY', :now)
                on conflict (tenant_id, principal_id) do update set last_digest_at = excluded.last_digest_at
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("me", principal)
            .param("now", now)
            .update()
    }

    private fun message(
        to: List<String>,
        items: List<Row>,
    ): Mail {
        val base = uiUrl.trimEnd('/')
        val text =
            items.joinToString("\n") {
                "• ${it.title}" + (it.body?.let { b -> " — $b" } ?: "") +
                    (it.link?.let { l -> "\n  $base$l" } ?: "")
            }
        val html =
            buildString {
                append(
                    "<div style=\"font-family:Arial,sans-serif;font-size:14px\"><h2 style=\"margin:0 0 8px\">What's new for you</h2><ul>",
                )
                for (n in items) {
                    val title = esc(n.title)
                    append("<li style=\"margin:4px 0\">")
                    append(if (n.link != null) "<a href=\"$base${n.link}\">$title</a>" else title)
                    n.body?.let { append(" <span style=\"color:#666\">— ${esc(it)}</span>") }
                    append("</li>")
                }
                append(
                    "</ul><p style=\"color:#777\"><a href=\"$base/preferences\">Change how often you get these</a></p></div>",
                )
            }
        val subject = if (items.size == 1) "1 new notification" else "${items.size} new notifications"
        return Mail(to, subject, text, html, origin = "digest")
    }

    private fun esc(s: String) =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    companion object {
        const val TYPE = "notifications.digest"
    }
}
