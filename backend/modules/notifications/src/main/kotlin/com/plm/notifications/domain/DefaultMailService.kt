package com.plm.notifications.domain

import com.plm.identity.api.PrincipalDirectory
import com.plm.notifications.api.Mail
import com.plm.notifications.api.MailService
import com.plm.notifications.api.SentMail
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private val log = LoggerFactory.getLogger(DefaultMailService::class.java)

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class MailScheduling

@Service
internal class DefaultMailService(
    private val jdbc: JdbcClient,
    private val directory: PrincipalDirectory,
    private val sender: ObjectProvider<JavaMailSender>,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @param:Value("\${plm.mail.enabled:false}") private val enabled: Boolean,
    @param:Value("\${plm.mail.from:PLM <plm@localhost>}") private val from: String,
) : MailService {
    private val json = JsonMapper.builder().build()

    override fun queue(
        tenant: TenantId,
        mail: Mail,
    ): UUID {
        val id = UuidV7.generate(clock)
        val to =
            mail.to
                .map { it.trim() }
                .filter { it.contains('@') }
                .distinct()
        jdbc
            .sql(
                """
                insert into mail_outbox (id, tenant_id, recipients, subject, text_body, html_body, origin, status, created_at)
                values (:id, :tenant, cast(:to as jsonb), :subject, :text, :html, :origin, :status, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", tenant.value)
            .param("to", json.writeValueAsString(to))
            .param("subject", mail.subject.take(250))
            .param("text", mail.text)
            .param("html", mail.html)
            .param("origin", mail.origin)
            // Nobody to send to: kept, as failed, so it shows in the log.
            .param("status", if (to.isEmpty()) "FAILED" else "PENDING")
            .param("now", Timestamp.from(clock.instant()))
            .update()
        return id
    }

    override fun addresses(
        tenant: TenantId,
        people: List<PrincipalId>,
    ): List<String> {
        val system = Actor(tenant, PrincipalId("system"), siteAdmin = true)
        return people.mapNotNull { runCatching { directory.get(system, it).email }.getOrNull() }.distinct()
    }

    @Suppress("UNCHECKED_CAST")
    override fun recent(
        tenant: TenantId,
        limit: Int,
    ): List<SentMail> =
        jdbc
            .sql("select * from mail_outbox where tenant_id = :tenant order by seq desc limit :limit")
            .param("tenant", tenant.value)
            .param("limit", limit)
            .query { rs, _ ->
                SentMail(
                    id = rs.getObject("id", UUID::class.java),
                    to = json.readValue(rs.getString("recipients"), List::class.java) as List<String>,
                    subject = rs.getString("subject"),
                    origin = rs.getString("origin"),
                    status = rs.getString("status"),
                    error = rs.getString("error"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    sentAt = rs.getTimestamp("sent_at")?.toInstant(),
                )
            }.list()

    @Scheduled(fixedDelayString = "\${plm.mail.interval:5s}", initialDelayString = "\${plm.mail.initial-delay:10s}")
    fun send() {
        if (!enabled) return
        val mailer = sender.ifAvailable ?: return
        val batch =
            tx.execute {
                jdbc
                    .sql(
                        """
                        update mail_outbox set attempts = attempts + 1 where id in (
                            select id from mail_outbox where status = 'PENDING' order by created_at limit 20 for update skip locked)
                        returning id, recipients, subject, text_body, html_body, attempts
                        """.trimIndent(),
                    ).query { rs, _ -> rs.getObject("id", UUID::class.java) }
                    .list()
            }
        for (id in batch) {
            val row =
                jdbc
                    .sql("select * from mail_outbox where id = :id")
                    .param("id", id)
                    .query { rs, _ ->
                        @Suppress("UNCHECKED_CAST")
                        Triple(
                            json.readValue(rs.getString("recipients"), List::class.java) as List<String>,
                            rs.getString("subject"),
                            rs.getString("text_body") to rs.getString("html_body"),
                        ) to
                            rs.getInt("attempts")
                    }.single()
            val (mail, attempts) = row
            try {
                val message: MimeMessage = mailer.createMimeMessage()
                MimeMessageHelper(message, true, "UTF-8").apply {
                    setFrom(from)
                    setTo(mail.first.toTypedArray())
                    setSubject(mail.second)
                    if (mail.third.second !=
                        null
                    ) {
                        setText(mail.third.first, mail.third.second)
                    } else {
                        setText(mail.third.first)
                    }
                }
                mailer.send(message)
                jdbc
                    .sql(
                        "update mail_outbox set status = 'SENT', sent_at = now(), error = null where id = :id",
                    ).param("id", id)
                    .update()
            } catch (e: Exception) {
                log.info("Mail {} not sent: {}", id, e.toString())
                jdbc
                    .sql("update mail_outbox set status = :status, error = :error where id = :id")
                    .param("id", id)
                    .param("status", if (attempts >= 5) "FAILED" else "PENDING")
                    .param("error", e.message?.take(500))
                    .update()
            }
        }
    }
}
