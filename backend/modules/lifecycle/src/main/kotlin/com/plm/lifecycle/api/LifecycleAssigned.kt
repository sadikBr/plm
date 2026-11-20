package com.plm.lifecycle.api

import com.plm.platform.events.DomainEvent
import java.util.UUID

data class LifecycleAssigned(
    val typeId: UUID,
    val templateId: UUID,
    val templateName: String,
) : DomainEvent {
    override val eventType = "lifecycle.LifecycleAssigned.v1"
    override val aggregateType = "type"
    override val aggregateId = typeId
}
