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
    val role: String,
    val previousRole: String?,
) : DomainEvent {
    override val eventType = "access.TeamMemberPut.v1"
    override val aggregateType = CONTEXT
    override val aggregateId = contextId
}

data class TeamMemberRemoved(
    val contextId: UUID,
    val principalId: String,
    val role: String,
) : DomainEvent {
    override val eventType = "access.TeamMemberRemoved.v1"
    override val aggregateType = CONTEXT
    override val aggregateId = contextId
}

data class PolicyChanged(
    val policyId: UUID,
    val contextId: UUID,
    val role: String,
    val typeId: UUID?,
    val state: String?,
    val effect: String,
    /** Permission names after the change; empty when the policy was deleted. */
    val permissions: List<String>,
    val previousPermissions: List<String>?,
    val deleted: Boolean,
) : DomainEvent {
    override val eventType = "access.PolicyChanged.v1"
    override val aggregateType = CONTEXT
    override val aggregateId = contextId
}

data class TeamRoleChanged(
    val role: String,
    val labels: Map<String, String>,
    val active: Boolean,
    val created: Boolean,
) : DomainEvent {
    override val eventType = "access.TeamRoleChanged.v1"
    override val aggregateType = "team-role"
    override val aggregateId: UUID = UUID.nameUUIDFromBytes("team-role:$role".toByteArray())
}
