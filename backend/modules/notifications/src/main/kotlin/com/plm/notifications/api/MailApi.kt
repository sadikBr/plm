package com.plm.notifications.api

import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import java.time.Instant
import java.util.UUID

data class Mail(
    /** E-mail addresses. */
    val to: List<String>,
    val subject: String,
    val text: String,
    val html: String? = null,
    val origin: String? = null,
)

data class SentMail(
    val id: UUID,
    val to: List<String>,
    val subject: String,
    val origin: String?,
    /** PENDING, SENT, FAILED */
    val status: String,
    val error: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
)

/**
 * Outgoing mail (roadmap 3.8). Queued in the caller's transaction (nothing goes out for a change
 * that rolled back) and sent in the background over SMTP (`spring.mail.*`; Mailpit in development).
 */
interface MailService {
    fun queue(
        tenant: TenantId,
        mail: Mail,
    ): UUID

    /** The e-mail addresses of these people, where known (from their sign-in). */
    fun addresses(
        tenant: TenantId,
        people: List<PrincipalId>,
    ): List<String>

    /** Latest first: for administrators and tests. */
    fun recent(
        tenant: TenantId,
        limit: Int = 50,
    ): List<SentMail>
}
