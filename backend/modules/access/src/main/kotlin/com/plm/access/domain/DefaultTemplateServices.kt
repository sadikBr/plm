package com.plm.access.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Context
import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.ContextTemplate
import com.plm.access.api.ContextTemplateChanged
import com.plm.access.api.ContextTemplateId
import com.plm.access.api.ContextTemplateService
import com.plm.access.api.CreateContext
import com.plm.access.api.CreatePolicy
import com.plm.access.api.Permission
import com.plm.access.api.PolicyService
import com.plm.access.api.SaveContextTemplate
import com.plm.access.api.SharedTeam
import com.plm.access.api.SharedTeamChanged
import com.plm.access.api.SharedTeamId
import com.plm.access.api.SharedTeamMember
import com.plm.access.api.SharedTeamService
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.access.api.TemplateContent
import com.plm.access.api.TemplateMember
import com.plm.access.api.TemplatePolicy
import com.plm.access.infra.AccessRepository
import com.plm.access.infra.TemplateRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

private val STATE_KEY = Regex("^[A-Z][A-Z0-9_]{0,39}$")

private fun requireAdmin(
    actor: Actor,
    what: String,
) {
    if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators can $what")
}

/**
 * Who administers something scoped to [organizationId]: site administrators for site-wide things,
 * the organization's managers (MANAGE on it) for its own (roadmap 1.14).
 */
private fun requireScopeAdmin(
    actor: Actor,
    organizationId: ContextId?,
    contexts: ContextService,
    access: AccessService,
    what: String,
) {
    if (organizationId == null) return requireAdmin(actor, "manage site-wide $what")
    val org = contexts.get(actor, organizationId)
    if (org.kind != ContextKind.ORGANIZATION) {
        throw ValidationException(
            "INVALID_SCOPE",
            "${org.name} is not an organization",
            mapOf(
                "errors" to listOf("${org.name} is not an organization"),
            ),
        )
    }
    access.require(actor, AccessTarget(org.id), Permission.MANAGE)
}

/**
 * Whether something scoped to [organizationId] is visible to [actor]: site-wide, or its organization
 * is. Asked of the repository: a failed lookup through the service would spoil the transaction.
 */
private fun visibleScope(
    actor: Actor,
    organizationId: ContextId?,
    access: AccessRepository,
) = organizationId == null || actor.siteAdmin || access.isVisible(actor.tenant, actor.principal, organizationId)

@Service
@Transactional
internal class DefaultSharedTeamService(
    private val teams: TemplateRepository,
    private val access: AccessRepository,
    private val contexts: ContextService,
    private val accessService: AccessService,
    private val events: DomainEvents,
    private val clock: Clock,
) : SharedTeamService {
    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<SharedTeam> =
        teams.teams(actor.tenant).filter { visibleScope(actor, it.organizationId, access) }

    @Transactional(readOnly = true)
    override fun usableIn(
        actor: Actor,
        contextId: ContextId,
    ): List<SharedTeam> {
        val context = contexts.get(actor, contextId)
        val org = teams.organizationOf(context.id)
        return teams.teams(actor.tenant).filter { it.organizationId == null || it.organizationId == org }
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: SharedTeamId,
    ): SharedTeam =
        teams.team(actor.tenant, id)
            ?: throw NotFoundException(
                "SHARED_TEAM_NOT_FOUND",
                "Shared team $id does not exist",
                mapOf(
                    "teamId" to id.value,
                ),
            )

    override fun create(
        actor: Actor,
        name: String,
        description: String?,
        organizationId: ContextId?,
    ): SharedTeam {
        requireScopeAdmin(actor, organizationId, contexts, accessService, "shared teams")
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 200) {
            throw ValidationException(
                "INVALID_SHARED_TEAM",
                "Name must be 1-200 characters",
                mapOf(
                    "errors" to listOf("Name must be 1-200 characters"),
                ),
            )
        }
        val team =
            SharedTeam(
                SharedTeamId(UuidV7.generate(clock)),
                trimmed,
                description?.trim()?.ifEmpty { null },
                organizationId,
                emptyList(),
                now(),
                actor.principal.value,
            )
        teams.insertTeam(actor.tenant, team)
        events.publish(actor, SharedTeamChanged(team.id.value, team.name, "created"))
        return team
    }

    override fun delete(
        actor: Actor,
        id: SharedTeamId,
    ) {
        val team = get(actor, id)
        requireScopeAdmin(actor, team.organizationId, contexts, accessService, "shared teams")
        teams.deleteTeam(id)
        events.publish(actor, SharedTeamChanged(id.value, team.name, "deleted"))
    }

    override fun putMember(
        actor: Actor,
        id: SharedTeamId,
        principalId: PrincipalId,
        role: TeamRole,
    ): SharedTeam {
        val team = get(actor, id)
        requireScopeAdmin(actor, team.organizationId, contexts, accessService, "shared teams")
        val definition = access.role(actor.tenant, role)
        if (definition == null || !definition.active) {
            throw ValidationException(
                "UNKNOWN_ROLE",
                "There is no active role '${role.key}'",
                mapOf("role" to role.key),
            )
        }
        teams.putTeamMember(actor.tenant, id, SharedTeamMember(principalId, role, now(), actor.principal.value))
        events.publish(actor, SharedTeamChanged(id.value, team.name, "member put ${principalId.value} as ${role.key}"))
        return get(actor, id)
    }

    override fun removeMember(
        actor: Actor,
        id: SharedTeamId,
        principalId: PrincipalId,
    ): SharedTeam {
        val team = get(actor, id)
        requireScopeAdmin(actor, team.organizationId, contexts, accessService, "shared teams")
        if (teams.removeTeamMember(id, principalId) == 0) {
            throw NotFoundException("MEMBER_NOT_FOUND", "$principalId is not on this team")
        }
        events.publish(actor, SharedTeamChanged(id.value, team.name, "member removed ${principalId.value}"))
        return get(actor, id)
    }

    @Transactional(readOnly = true)
    override fun attached(
        actor: Actor,
        contextId: ContextId,
    ): List<SharedTeam> {
        val context = contexts.get(actor, contextId)
        return teams.attachedTeams(context.id).mapNotNull { teams.team(actor.tenant, it) }
    }

    override fun attach(
        actor: Actor,
        contextId: ContextId,
        id: SharedTeamId,
    ) {
        val context = contexts.get(actor, contextId)
        accessService.require(actor, AccessTarget(context.id), Permission.MANAGE)
        val team = get(actor, id)
        if (team.organizationId != null && team.organizationId != teams.organizationOf(context.id)) {
            throw ValidationException("TEAM_NOT_USABLE_HERE", "${team.name} belongs to another organization")
        }
        teams.attach(actor.tenant, context.id, id, now(), actor.principal.value)
        events.publish(actor, SharedTeamChanged(id.value, team.name, "attached to ${context.name} (${context.id})"))
    }

    override fun detach(
        actor: Actor,
        contextId: ContextId,
        id: SharedTeamId,
    ) {
        val context = contexts.get(actor, contextId)
        accessService.require(actor, AccessTarget(context.id), Permission.MANAGE)
        val team = get(actor, id)
        if (teams.detach(context.id, id) == 0) {
            throw NotFoundException("SHARED_TEAM_NOT_FOUND", "${team.name} is not attached to ${context.name}")
        }
        events.publish(actor, SharedTeamChanged(id.value, team.name, "detached from ${context.name} (${context.id})"))
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
}

@Service
@Transactional
internal class DefaultContextTemplateService(
    private val templates: TemplateRepository,
    private val accessService: AccessService,
    private val access: AccessRepository,
    private val contexts: ContextService,
    private val policies: PolicyService,
    private val teams: TeamService,
    private val sharedTeams: SharedTeamService,
    private val types: TypeService,
    private val events: DomainEvents,
    private val clock: Clock,
) : ContextTemplateService {
    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        kind: ContextKind?,
        parentId: ContextId?,
    ): List<ContextTemplate> {
        val parentOrg = parentId?.let { templates.organizationOf(contexts.get(actor, it).id) }
        return templates.templates(actor.tenant).filter {
            (kind == null || it.kind == kind) &&
                visibleScope(actor, it.organizationId, access) &&
                (parentId == null || it.organizationId == null || it.organizationId == parentOrg)
        }
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: ContextTemplateId,
    ): ContextTemplate =
        templates.template(actor.tenant, id)
            ?: throw NotFoundException(
                "TEMPLATE_NOT_FOUND",
                "Template $id does not exist",
                mapOf(
                    "templateId" to id.value,
                ),
            )

    override fun create(
        actor: Actor,
        command: SaveContextTemplate,
    ): ContextTemplate {
        requireScopeAdmin(actor, command.organizationId, contexts, accessService, "context templates")
        val now = now()
        return save(
            actor,
            ContextTemplate(
                ContextTemplateId(UuidV7.generate(clock)),
                "",
                command.kind,
                null,
                TemplateContent(),
                command.organizationId,
                now,
                actor.principal.value,
                now,
            ),
            command,
        )
    }

    override fun update(
        actor: Actor,
        id: ContextTemplateId,
        command: SaveContextTemplate,
    ): ContextTemplate {
        val existing = get(actor, id)
        requireScopeAdmin(actor, existing.organizationId, contexts, accessService, "context templates")
        return save(actor, existing, command.copy(organizationId = existing.organizationId))
    }

    override fun delete(
        actor: Actor,
        id: ContextTemplateId,
    ) {
        val template = get(actor, id)
        requireScopeAdmin(actor, template.organizationId, contexts, accessService, "context templates")
        templates.deleteTemplate(id)
        events.publish(actor, ContextTemplateChanged(id.value, template.name, template.kind.name, deleted = true))
    }

    override fun saveFromContext(
        actor: Actor,
        contextId: ContextId,
        name: String,
        description: String?,
        siteWide: Boolean,
    ): ContextTemplate {
        val context = contexts.get(actor, contextId)
        val scope =
            if (siteWide ||
                context.kind == ContextKind.ORGANIZATION
            ) {
                null
            } else {
                templates.organizationOf(context.id)
            }
        if (context.kind == ContextKind.SITE) {
            throw ValidationException(
                "INVALID_TEMPLATE",
                "The site can't be saved as a template",
                mapOf(
                    "errors" to listOf("The site can't be saved as a template"),
                ),
            )
        }
        val typeNames = types.list(actor).associate { it.id to it.name }
        val content =
            TemplateContent(
                policies =
                    access.policiesInEffect(context.id).filter { it.contextId == context.id }.map {
                        TemplatePolicy(
                            it.role.key,
                            it.typeId?.let(typeNames::getValue),
                            it.state,
                            it.effect,
                            it.permissions,
                        )
                    },
                members = teams.members(actor, context.id).map { TemplateMember(it.principalId.value, it.role.key) },
                sharedTeams = sharedTeams.attached(actor, context.id).map { it.name },
            )
        return create(actor, SaveContextTemplate(name, context.kind, description, content, scope))
    }

    override fun createContext(
        actor: Actor,
        command: CreateContext,
        templateId: ContextTemplateId,
    ): Context {
        val template = get(actor, templateId)
        val parentOrg = templates.organizationOf(contexts.get(actor, command.parentId).id)
        if (template.organizationId != null && template.organizationId != parentOrg) {
            throw ValidationException("TEMPLATE_NOT_USABLE_HERE", "'${template.name}' belongs to another organization")
        }
        if (template.kind != command.kind) {
            throw ValidationException(
                "TEMPLATE_KIND_MISMATCH",
                "'${template.name}' is a ${template.kind.name.lowercase()} template, not a ${command.kind.name.lowercase()} one",
            )
        }
        // The template may have gone stale (a role deactivated, a type renamed): say so before creating anything.
        failIfAny(errors(actor, template.content, template.organizationId))
        val context = contexts.create(actor, command)
        val typeIds = types.list(actor).associate { it.name to it.id }
        val teamIds = sharedTeams.usableIn(actor, context.id).associate { it.name to it.id }
        for (m in template.content.members) {
            // The creator stays MANAGER of what they create.
            if (m.principalId !=
                actor.principal.value
            ) {
                teams.put(actor, context.id, PrincipalId(m.principalId), TeamRole(m.role))
            }
        }
        for (p in template.content.policies) {
            policies.create(
                actor,
                context.id,
                CreatePolicy(TeamRole(p.role), p.type?.let(typeIds::getValue), p.state, p.effect, p.permissions),
            )
        }
        for (name in template.content.sharedTeams) sharedTeams.attach(actor, context.id, teamIds.getValue(name))
        return context
    }

    private fun save(
        actor: Actor,
        existing: ContextTemplate,
        command: SaveContextTemplate,
    ): ContextTemplate {
        val name = command.name.trim()
        val problems = mutableListOf<String>()
        if (name.isEmpty() || name.length > 200) problems += "Name must be 1-200 characters"
        if (command.kind ==
            ContextKind.SITE
        ) {
            problems +=
                "There is only one site: templates are for organizations, products, libraries and projects"
        }
        if (command.kind == ContextKind.ORGANIZATION && existing.organizationId != null) {
            problems += "Organization templates are site-wide: organizations are created on the site"
        }
        problems += errors(actor, command.content, existing.organizationId)
        failIfAny(problems)
        val saved =
            existing.copy(
                name = name,
                kind = command.kind,
                description = command.description?.trim()?.ifEmpty { null },
                content = command.content,
                updatedAt = now(),
            )
        templates.saveTemplate(actor.tenant, saved)
        events.publish(actor, ContextTemplateChanged(saved.id.value, saved.name, saved.kind.name, deleted = false))
        return saved
    }

    /** Everything wrong with [content] in this tenant, in words an admin can act on. */
    private fun errors(
        actor: Actor,
        content: TemplateContent,
        organizationId: ContextId?,
    ): List<String> {
        val errors = mutableListOf<String>()
        val roles =
            access
                .roles(actor.tenant)
                .filter { it.active }
                .map { it.role.key }
                .toSet()
        val typeNames = types.list(actor).map { it.name }.toSet()
        // A template may use site-wide teams, and its own organization's.
        val teamNames =
            templates
                .teams(actor.tenant)
                .filter {
                    it.organizationId == null || it.organizationId == organizationId
                }.map { it.name }
                .toSet()
        content.policies.forEachIndexed { i, p ->
            val rule = "Rule ${i + 1}"
            if (p.role !in roles) errors += "$rule: no active role '${p.role}'"
            if (p.type != null && p.type !in typeNames) errors += "$rule: no type '${p.type}'"
            if (p.state != null && !STATE_KEY.matches(p.state)) errors += "$rule: '${p.state}' is not a state key"
            if (p.permissions.isEmpty()) errors += "$rule: no permissions"
        }
        content.policies
            .groupBy {
                listOf(
                    it.role,
                    it.type,
                    it.state,
                    it.effect,
                )
            }.filterValues { it.size > 1 }
            .keys
            .forEach {
                errors +=
                    "Two ${it[3]} rules for role ${it[0]}, type ${it[1] ?: "all"}, state ${it[2] ?: "any"}: merge them"
            }
        content.members.forEach { m ->
            if (m.principalId.isBlank()) errors += "A member has no user"
            if (m.role !in roles) errors += "Member ${m.principalId}: no active role '${m.role}'"
        }
        content.sharedTeams.filter { it !in teamNames }.forEach { errors += "No shared team '$it'" }
        return errors
    }

    private fun failIfAny(errors: List<String>) {
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_TEMPLATE",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
