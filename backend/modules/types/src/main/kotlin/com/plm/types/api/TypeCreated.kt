package com.plm.types.api

import com.plm.platform.events.DomainEvent
import java.util.UUID

data class TypeCreated(
    val typeId: UUID,
    val parentId: UUID,
    val name: String,
    val baseClass: BaseClass,
) : DomainEvent {
    override val eventType = "types.TypeCreated.v1"
    override val aggregateType = "type"
    override val aggregateId = typeId
}

data class TypePropertiesChanged(
    val typeId: java.util.UUID,
    val name: String,
    val displayName: String,
    val instantiable: Boolean,
) : com.plm.platform.events.DomainEvent {
    override val eventType = "types.TypePropertiesChanged.v1"
    override val aggregateType = "type"
    override val aggregateId = typeId
}
