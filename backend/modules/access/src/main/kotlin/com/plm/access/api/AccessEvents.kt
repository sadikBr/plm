package com.plm.access.api

import com.plm.platform.events.DomainEvent
import java.util.UUID

private const val CONTEXT = "context"

data class ContextCreated(
    val contextId: UUID,
    val parentId: UUID,
    val kind: ContextKind,
    val name: String,
) : DomainEvent {
    override val eventType = "access.ContextCreated.v1"
    override val aggregateType = CONTEXT
    override val aggregateId = contextId
}

data class TeamMemberPut(
    val contextId: UUID,
    val principalId: String,
    val role: TeamRole,
    val previousRole: TeamRole?,
) : DomainEvent {
    override val eventType = "access.TeamMemberPut.v1"
    override val aggregateType = CONTEXT
    override val aggregateId = contextId
}

data class TeamMemberRemoved(
    val contextId: UUID,
    val principalId: String,
    val role: TeamRole,
) : DomainEvent {
    override val eventType = "access.TeamMemberRemoved.v1"
    override val aggregateType = CONTEXT
    override val aggregateId = contextId
}
