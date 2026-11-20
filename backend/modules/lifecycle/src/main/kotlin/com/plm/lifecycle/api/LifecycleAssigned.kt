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

data class LifecycleVersioned(
    val templateId: java.util.UUID,
    val previousId: java.util.UUID,
    val name: String,
    val version: Int,
) : com.plm.platform.events.DomainEvent {
    override val eventType = "lifecycle.LifecycleVersioned.v1"
    override val aggregateType = "lifecycle-template"
    override val aggregateId = templateId
}
