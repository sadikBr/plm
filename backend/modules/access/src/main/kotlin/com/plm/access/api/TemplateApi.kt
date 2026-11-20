package com.plm.access.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.platform.id.PrincipalId
import java.time.Instant
import java.util.UUID

// ---------- Shared teams ----------

@JvmInline
value class SharedTeamId(
    val value: UUID,
) {
    override fun toString() = value.toString()
}

data class SharedTeamMember(
    val principalId: PrincipalId,
    val role: TeamRole,
    val addedAt: Instant,
    val addedBy: String,
)

data class SharedTeam(
    val id: SharedTeamId,
    val name: String,
    val description: String?,
    val members: List<SharedTeamMember>,
    val createdAt: Instant,
    val createdBy: String,
)

/**
 * Shared teams (roadmap 1.12): a group of people with roles, maintained in one place and attached
 * to any number of contexts. Members hold their role in every context the team is attached to,
 * and below it. Site administrators manage the teams; a context's managers attach and detach them.
 *
 * Error codes: `ADMIN_REQUIRED`, `ACCESS_DENIED`, `SHARED_TEAM_NOT_FOUND`, `INVALID_SHARED_TEAM`,
 * `DUPLICATE_SHARED_TEAM`, `UNKNOWN_ROLE`, `MEMBER_NOT_FOUND`.
 */
interface SharedTeamService {
    fun list(actor: Actor): List<SharedTeam>

    fun get(
        actor: Actor,
        id: SharedTeamId,
    ): SharedTeam

    fun create(
        actor: Actor,
        name: String,
        description: String?,
    ): SharedTeam

    fun delete(
        actor: Actor,
        id: SharedTeamId,
    )

    fun putMember(
        actor: Actor,
        id: SharedTeamId,
        principalId: PrincipalId,
        role: TeamRole,
    ): SharedTeam

    fun removeMember(
        actor: Actor,
        id: SharedTeamId,
        principalId: PrincipalId,
    ): SharedTeam

    /** Teams attached to exactly this context. Needs the context to be visible. */
    fun attached(
        actor: Actor,
        contextId: ContextId,
    ): List<SharedTeam>

    /** Needs MANAGE on the context. Attaching twice is a no-op. */
    fun attach(
        actor: Actor,
        contextId: ContextId,
        id: SharedTeamId,
    )

    fun detach(
        actor: Actor,
        contextId: ContextId,
        id: SharedTeamId,
    )
}

// ---------- Context templates ----------

@JvmInline
value class ContextTemplateId(
    val value: UUID,
) {
    override fun toString() = value.toString()
}

/** An access rule in a template. Types are referred to by name, so templates travel between environments. */
data class TemplatePolicy(
    val role: String,
    val type: String? = null,
    val state: String? = null,
    val effect: PolicyEffect = PolicyEffect.ALLOW,
    val permissions: Set<Permission>,
)

data class TemplateMember(
    val principalId: String,
    val role: String,
)

/** What a new context starts with, besides its creator as MANAGER. */
data class TemplateContent(
    val policies: List<TemplatePolicy> = emptyList(),
    val members: List<TemplateMember> = emptyList(),
    /** Names of shared teams to attach. */
    val sharedTeams: List<String> = emptyList(),
)

data class ContextTemplate(
    val id: ContextTemplateId,
    val name: String,
    /** The kind of context it creates. */
    val kind: ContextKind,
    val description: String?,
    val content: TemplateContent,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant,
)

data class SaveContextTemplate(
    val name: String,
    val kind: ContextKind,
    val description: String? = null,
    val content: TemplateContent = TemplateContent(),
)

/**
 * Context templates (roadmap 1.12). Everyone can see them (to pick one when creating a context);
 * site administrators create, change and delete them. Error codes: `ADMIN_REQUIRED`,
 * `TEMPLATE_NOT_FOUND`, `INVALID_TEMPLATE`, `DUPLICATE_TEMPLATE`, `TEMPLATE_KIND_MISMATCH`.
 */
interface ContextTemplateService {
    fun list(
        actor: Actor,
        kind: ContextKind? = null,
    ): List<ContextTemplate>

    fun get(
        actor: Actor,
        id: ContextTemplateId,
    ): ContextTemplate

    fun create(
        actor: Actor,
        command: SaveContextTemplate,
    ): ContextTemplate

    fun update(
        actor: Actor,
        id: ContextTemplateId,
        command: SaveContextTemplate,
    ): ContextTemplate

    fun delete(
        actor: Actor,
        id: ContextTemplateId,
    )

    /** A template from an existing context: its own access rules, team and attached shared teams. */
    fun saveFromContext(
        actor: Actor,
        contextId: ContextId,
        name: String,
        description: String?,
    ): ContextTemplate

    /**
     * Creates a context (same rules as [ContextService.create]) and applies [templateId] to it, in
     * one transaction. The template's kind must match (`TEMPLATE_KIND_MISMATCH`).
     */
    fun createContext(
        actor: Actor,
        command: CreateContext,
        templateId: ContextTemplateId,
    ): Context
}

data class ContextTemplateChanged(
    val templateId: UUID,
    val name: String,
    val kind: String,
    val deleted: Boolean,
) : DomainEvent {
    override val eventType = "access.ContextTemplateChanged.v1"
    override val aggregateType = "context-template"
    override val aggregateId = templateId
}

data class SharedTeamChanged(
    val teamId: UUID,
    val name: String,
    /** e.g. `created`, `deleted`, `member put bob as MEMBER`, `attached to <context>`. */
    val change: String,
) : DomainEvent {
    override val eventType = "access.SharedTeamChanged.v1"
    override val aggregateType = "shared-team"
    override val aggregateId = teamId
}
