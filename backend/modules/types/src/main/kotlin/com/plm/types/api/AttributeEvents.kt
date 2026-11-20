package com.plm.types.api

import com.plm.platform.events.DomainEvent
import java.util.UUID

data class AttributeDefined(
    val attributeId: UUID,
    val typeId: UUID,
    val name: String,
    val dataType: DataType,
) : DomainEvent {
    override val eventType = "types.AttributeDefined.v1"
    override val aggregateType = "type"
    override val aggregateId = typeId
}

data class AttributeUpdated(
    val attributeId: UUID,
    val typeId: UUID,
    val name: String,
    val before: Map<String, Any?>,
    val after: Map<String, Any?>,
) : DomainEvent {
    override val eventType = "types.AttributeUpdated.v1"
    override val aggregateType = "type"
    override val aggregateId = typeId
}

data class EnumerationChanged(
    val enumerationId: UUID,
    val name: String,
    val keys: List<String>,
    val activeKeys: List<String>,
) : DomainEvent {
    override val eventType = "types.EnumerationChanged.v1"
    override val aggregateType = "enumeration"
    override val aggregateId = enumerationId
}

data class TypeRulesChanged(
    val typeId: UUID,
    val numberingSchemeId: UUID?,
    val revisionScheme: String?,
    val allowManualNumber: Boolean?,
) : DomainEvent {
    override val eventType = "types.TypeRulesChanged.v1"
    override val aggregateType = "type"
    override val aggregateId = typeId
}
