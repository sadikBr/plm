package com.plm.documents.domain

import com.plm.documents.infra.DocumentTemplateRepository
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.TenantId
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

/** A deleted object is no template any more. */
@Component
internal class TemplateCleanup(
    private val templates: DocumentTemplateRepository,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "versioning.ObjectDeleted.v1") return
        val master = UUID.fromString(envelope.payload["objectId"].toString())
        val tenant = TenantId(envelope.tenantId)
        templates.all(tenant).filter { it.masterId == master }.forEach { templates.delete(tenant, it.id) }
    }
}
