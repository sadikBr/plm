package com.plm.access.domain

import com.plm.platform.events.EventEnvelope
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * A person the directory made before they signed in gets their real id at first sign-in
 * (`identity.PrincipalRekeyed.v1`, roadmap 9.1): their team memberships follow.
 */
@Component
internal class PrincipalRekeyListener(
    private val jdbc: JdbcClient,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "identity.PrincipalRekeyed.v1") return
        val from = envelope.payload["previousId"] as? String ?: return
        val to = envelope.payload["newId"] as? String ?: return
        for (table in listOf("context_member", "shared_team_member")) {
            jdbc
                .sql("update $table set principal_id = :to where tenant_id = :tenant and principal_id = :from")
                .param("tenant", envelope.tenantId)
                .param("from", from)
                .param("to", to)
                .update()
        }
    }
}
