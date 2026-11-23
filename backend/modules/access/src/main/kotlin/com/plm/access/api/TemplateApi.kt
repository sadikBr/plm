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
    /** Set for a team that belongs to one organization: managed by its managers, attachable only inside it. */
    val organizationId: ContextId?,
    val members: List<SharedTeamMember>,
    val createdAt: Instant,
    val createdBy: String,
    /** The directory group (roadmap 9.1) that manages its members; null for a team managed here. */
    val directoryGroup: String? = null,
)

/**
 * Shared teams (roadmap 1.12): a group of people with roles, maintained in one place and attached
 * to any number of contexts. Members hold their role in every context the team is attached to,
 * and below it. Site-wide teams are managed by site administrators; an organization's own teams by
 * its managers (roadmap 1.14). A context's managers attach and detach teams usable there.
 *
 * Error codes: `ADMIN_REQUIRED`, `ACCESS_DENIED`, `SHARED_TEAM_NOT_FOUND`, `INVALID_SHARED_TEAM`,
 * `DUPLICATE_SHARED_TEAM`, `UNKNOWN_ROLE`, `MEMBER_NOT_FOUND`, `DIRECTORY_MANAGED` (a directory
 * team's members and name change only through the directory).
 */
interface SharedTeamService {
    /** Teams the actor can see: site-wide ones and those of organizations visible to them. */
    fun list(actor: Actor): List<SharedTeam>

    /** Teams that can be attached to [contextId]: site-wide ones and its organization's. */
    fun usableIn(
        actor: Actor,
        contextId: ContextId,
    ): List<SharedTeam>

    fun get(
        actor: Actor,
        id: SharedTeamId,
    ): SharedTeam

    /** [organizationId] null: a site-wide team (site administrators only). */
    fun create(
        actor: Actor,
        name: String,
        description: String?,
        organizationId: ContextId? = null,
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

    /** The contexts [id] is attached to. */
    fun attachedTo(
        actor: Actor,
        id: SharedTeamId,
    ): List<ContextId>

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

    /**
     * For the directory (roadmap 9.1), site administrators only: makes or renames the site-wide team
     * of directory group [group] and sets its members exactly. Returns the team.
     */
    fun syncDirectoryTeam(
        actor: Actor,
        group: String,
        name: String,
        members: Map<PrincipalId, TeamRole>,
    ): SharedTeam

    /** Deletes the team of directory group [group], if there is one. */
    fun deleteDirectoryTeam(
        actor: Actor,
        group: String,
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
    /** Set for an organization's own template: managed by its managers, usable only inside it. */
    val organizationId: ContextId?,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant,
)

data class SaveContextTemplate(
    val name: String,
    val kind: ContextKind,
    val description: String? = null,
    val content: TemplateContent = TemplateContent(),
    /** Null: site-wide (site administrators only). Can't be changed after creation. */
    val organizationId: ContextId? = null,
)

/**
 * Context templates (roadmap 1.12). Site-wide templates are managed by site administrators; an
 * organization's own templates by its managers and usable only inside it (roadmap 1.14). Error codes: `ADMIN_REQUIRED`,
 * `TEMPLATE_NOT_FOUND`, `INVALID_TEMPLATE`, `DUPLICATE_TEMPLATE`, `TEMPLATE_KIND_MISMATCH`.
 */
interface ContextTemplateService {
    /** Templates the actor can see; with [parentId], only those usable for a new context under it. */
    fun list(
        actor: Actor,
        kind: ContextKind? = null,
        parentId: ContextId? = null,
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

    /**
     * A template from an existing context: its own access rules, team and attached shared teams.
     * Site administrators may make it site-wide; otherwise it belongs to the context's organization
     * (needs MANAGE there).
     */
    fun saveFromContext(
        actor: Actor,
        contextId: ContextId,
        name: String,
        description: String?,
        siteWide: Boolean = false,
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
