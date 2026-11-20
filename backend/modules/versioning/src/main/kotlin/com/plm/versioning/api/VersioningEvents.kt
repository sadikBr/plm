package com.plm.versioning.api

import com.plm.platform.events.DomainEvent
import java.util.UUID

private const val AGGREGATE = "versioned-object"

/** Common shape: every versioning event names the object, where it lives and which iteration. */
sealed interface VersioningEvent : DomainEvent {
    val objectId: UUID
    val number: String
    val typeId: UUID
    val contextId: UUID
    val iterationId: UUID
    val version: String

    override val aggregateType get() = AGGREGATE
    override val aggregateId get() = objectId
}

data class ObjectCreated(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    override val iterationId: UUID,
    override val version: String,
    val name: String,
    val attributes: Map<String, Any?>,
) : VersioningEvent {
    override val eventType = "versioning.ObjectCreated.v1"
}

data class ObjectCheckedOut(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    /** The working copy. */
    override val iterationId: UUID,
    override val version: String,
) : VersioningEvent {
    override val eventType = "versioning.ObjectCheckedOut.v1"
}

data class WorkingCopyUpdated(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    override val iterationId: UUID,
    override val version: String,
    val before: Map<String, Any?>,
    val after: Map<String, Any?>,
) : VersioningEvent {
    override val eventType = "versioning.WorkingCopyUpdated.v1"
}

data class ObjectCheckedIn(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    override val iterationId: UUID,
    override val version: String,
    val comment: String?,
) : VersioningEvent {
    override val eventType = "versioning.ObjectCheckedIn.v1"
}

data class CheckOutUndone(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    /** The discarded working copy. */
    override val iterationId: UUID,
    override val version: String,
) : VersioningEvent {
    override val eventType = "versioning.CheckOutUndone.v1"
}

data class ObjectRevised(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    override val iterationId: UUID,
    override val version: String,
    val previousVersion: String,
) : VersioningEvent {
    override val eventType = "versioning.ObjectRevised.v1"
}
