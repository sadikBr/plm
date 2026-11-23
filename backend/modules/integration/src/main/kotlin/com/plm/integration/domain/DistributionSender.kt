package com.plm.integration.domain

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sends distribution messages to targets with an endpoint (roadmap 9.5): a signed POST, retried
 * with growing waits, then FAILED (resend from the object). Delivery means the ERP has it; its
 * acknowledgement says whether it was taken.
 */
@Component
internal class DistributionSender(
    private val repository: DistributionRepository,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @Value("\${plm.distribution.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(
                Duration.ofSeconds(5),
            ).followRedirects(HttpClient.Redirect.NEVER)
            .build()

    @Scheduled(
        fixedDelayString = "\${plm.distribution.poll-interval:2s}",
        initialDelayString = "\${plm.distribution.initial-delay:5s}",
    )
    fun poll() {
        if (enabled) sendDue()
    }

    fun sendDue(): Int {
        val now = clock.instant()
        val batch = tx.execute { repository.claim(now, now.plus(Duration.ofMinutes(2)), 50) }.orEmpty()
        batch.forEach(::send)
        return batch.size
    }

    private fun send(m: DueMessage) {
        val timestamp = clock.instant().epochSecond.toString()
        val error =
            try {
                val request =
                    HttpRequest
                        .newBuilder(URI(m.endpoint))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "PLM-Distribution/1")
                        .header("X-PLM-Message", m.id.toString())
                        .header("X-PLM-Timestamp", timestamp)
                        .header("X-PLM-Signature", sign(m.secret, timestamp, m.body))
                        .POST(HttpRequest.BodyPublishers.ofString(m.body))
                        .build()
                val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                if (status in 200..299) null else "The ERP answered $status"
            } catch (e: Exception) {
                "${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
            }
        tx.executeWithoutResult {
            if (error == null) {
                repository.sent(m.id, clock.instant())
                repository.objectRow(m.objectId, m.targetId)?.let {
                    if (it.status ==
                        "PENDING"
                    ) {
                        repository.saveObjectRow(it.copy(status = "SENT", updatedAt = clock.instant()))
                    }
                }
            } else {
                val attempt = m.attempts + 1
                val retryAt =
                    if (attempt >=
                        MAX_ATTEMPTS
                    ) {
                        null
                    } else {
                        clock.instant().plus(
                            Duration.ofSeconds(
                                30L shl (attempt - 1).coerceAtMost(10),
                            ),
                        )
                    }
                repository.failedAttempt(m.id, error, retryAt)
                if (retryAt == null) {
                    repository.objectRow(m.objectId, m.targetId)?.let {
                        repository.saveObjectRow(
                            it.copy(status = "ERROR", message = "Not delivered: $error", updatedAt = clock.instant()),
                        )
                    }
                }
                log.warn("Distribution message {} to {}: {}", m.id, m.endpoint, error)
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 8

        /** HMAC-SHA256 of `timestamp.body`, hex: what the ERP checks with the target's secret. */
        fun sign(
            secret: String,
            timestamp: String,
            body: String,
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal("$timestamp.$body".toByteArray()))
        }
    }
}
