package com.plm.access

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.ContextTemplateService
import com.plm.access.api.CreateContext
import com.plm.access.api.CreatePolicy
import com.plm.access.api.Permission.CREATE
import com.plm.access.api.Permission.MODIFY
import com.plm.access.api.Permission.READ
import com.plm.access.api.Permission.REVISE
import com.plm.access.api.PolicyEffect
import com.plm.access.api.PolicyService
import com.plm.access.api.SaveContextTemplate
import com.plm.access.api.SharedTeamService
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.access.api.TemplateContent
import com.plm.access.api.TemplateMember
import com.plm.access.api.TemplatePolicy
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.types.api.TypeId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class TemplateServiceIT(
    @Autowired private val access: AccessService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val sharedTeams: SharedTeamService,
    @Autowired private val templates: ContextTemplateService,
    @Autowired private val policies: PolicyService,
) {
    private val admin = Actors.admin

    private fun unique(prefix: String) = "$prefix ${UUID.randomUUID().toString().take(8)}"

    private inner class World {
        val org = contexts.create(admin, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, unique("Org")))
        val manager = Actors.user("manager").also { teams.put(admin, org.id, it.principal, TeamRole.MANAGER) }
        val qa = Actors.user("qa")
        val team =
            sharedTeams.create(admin, unique("Quality"), "QA engineers").also {
                sharedTeams.putMember(admin, it.id, qa.principal, TeamRole.MEMBER)
            }
    }

    private fun perms(
        actor: com.plm.platform.actor.Actor,
        context: ContextId,
    ) = access.permissions(actor, AccessTarget(context, TypeId.PART, "IN_WORK"))

    @Test
    fun `a shared team gives its members their role wherever it is attached`() {
        val w = World()
        val p1 = contexts.create(w.manager, CreateContext(w.org.id, ContextKind.PRODUCT, "P1"))
        val p2 = contexts.create(w.manager, CreateContext(w.org.id, ContextKind.PRODUCT, "P2"))
        assertThatThrownBy { contexts.get(w.qa, p1.id) }.isInstanceOf(NotFoundException::class.java)

        sharedTeams.attach(w.manager, p1.id, w.team.id)
        sharedTeams.attach(w.manager, p2.id, w.team.id)

        assertThat(perms(w.qa, p1.id)).containsExactlyInAnyOrder(READ, CREATE, MODIFY, REVISE)
        assertThat(perms(w.qa, p2.id)).contains(MODIFY)
        assertThat(contexts.get(w.qa, p1.id)).isEqualTo(p1)
        assertThat(
            teams
                .effectiveMembers(w.manager, p1.id)
                .single {
                    it.principalId == w.qa.principal
                }.sharedTeamId,
        ).isEqualTo(w.team.id)
        assertThat(sharedTeams.attached(w.manager, p1.id).map { it.id }).containsExactly(w.team.id)

        // One change to the team applies everywhere.
        sharedTeams.putMember(admin, w.team.id, w.qa.principal, TeamRole.VIEWER)
        assertThat(perms(w.qa, p2.id)).containsExactly(READ)
        sharedTeams.detach(w.manager, p1.id, w.team.id)
        assertThat(perms(w.qa, p1.id)).isEmpty()
        assertThat(perms(w.qa, p2.id)).containsExactly(READ)
    }

    @Test
    fun `admins manage shared teams, context managers attach them`() {
        val w = World()
        val p = contexts.create(w.manager, CreateContext(w.org.id, ContextKind.PRODUCT, "P"))
        val member = Actors.user("member").also { teams.put(w.manager, p.id, it.principal, TeamRole.MEMBER) }

        assertThatThrownBy { sharedTeams.create(w.manager, unique("Mine"), null) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ADMIN_REQUIRED") }
        assertThatThrownBy { sharedTeams.attach(member, p.id, w.team.id) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ACCESS_DENIED") }
        assertThatThrownBy { sharedTeams.putMember(admin, w.team.id, member.principal, TeamRole("NOPE")) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("UNKNOWN_ROLE") }
    }

    @Test
    fun `a product created from a template starts with its rules, team and shared teams`() {
        val w = World()
        val reviewer = Actors.user("reviewer")
        val template =
            templates.create(
                admin,
                SaveContextTemplate(
                    unique("Regulated product"),
                    ContextKind.PRODUCT,
                    "Viewers may edit parts",
                    TemplateContent(
                        policies = listOf(TemplatePolicy("VIEWER", type = "Part", permissions = setOf(MODIFY))),
                        members =
                            listOf(
                                TemplateMember(reviewer.principal.value, "VIEWER"),
                                TemplateMember(w.manager.principal.value, "VIEWER"),
                            ),
                        sharedTeams = listOf(w.team.name),
                    ),
                ),
            )

        val product =
            templates.createContext(
                w.manager,
                CreateContext(w.org.id, ContextKind.PRODUCT, "Pump"),
                template.id,
            )

        assertThat(perms(reviewer, product.id)).containsExactlyInAnyOrder(READ, MODIFY)
        assertThat(perms(w.qa, product.id)).contains(MODIFY)
        // The creator stays manager even though the template lists them as a viewer.
        assertThat(
            teams
                .members(w.manager, product.id)
                .single {
                    it.principalId == w.manager.principal
                }.role,
        ).isEqualTo(TeamRole.MANAGER)

        assertThatThrownBy {
            templates.createContext(w.manager, CreateContext(w.org.id, ContextKind.LIBRARY, "Lib"), template.id)
        }.isInstanceOfSatisfying(
            ValidationException::class.java,
        ) { assertThat(it.code).isEqualTo("TEMPLATE_KIND_MISMATCH") }
    }

    @Test
    fun `invalid and stale templates are refused before anything is created`() {
        val w = World()
        assertThatThrownBy {
            templates.create(
                admin,
                SaveContextTemplate(
                    unique("Broken"),
                    ContextKind.PRODUCT,
                    content =
                        TemplateContent(
                            policies =
                                listOf(
                                    TemplatePolicy(
                                        "GHOST",
                                        type = "NoSuchType",
                                        state = "released",
                                        permissions = emptySet(),
                                    ),
                                ),
                            sharedTeams = listOf("Nobody"),
                        ),
                ),
            )
        }.isInstanceOfSatisfying(ValidationException::class.java) {
            assertThat(it.code).isEqualTo("INVALID_TEMPLATE")
            assertThat(it.details["errors"] as List<*>).hasSize(5)
        }

        val template =
            templates.create(
                admin,
                SaveContextTemplate(
                    unique("With team"),
                    ContextKind.PRODUCT,
                    content = TemplateContent(sharedTeams = listOf(w.team.name)),
                ),
            )
        sharedTeams.delete(admin, w.team.id)

        assertThatThrownBy {
            templates.createContext(w.manager, CreateContext(w.org.id, ContextKind.PRODUCT, "Stale"), template.id)
        }.isInstanceOfSatisfying(
            ValidationException::class.java,
        ) { assertThat(it.code).isEqualTo("INVALID_TEMPLATE") }
        assertThat(contexts.children(admin, w.org.id).map { it.name }).doesNotContain("Stale")
        assertThatThrownBy { templates.create(w.manager, SaveContextTemplate(unique("Mine"), ContextKind.PRODUCT)) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `an existing context can be saved as a template and reproduced`() {
        val w = World()
        val source = contexts.create(w.manager, CreateContext(w.org.id, ContextKind.PROJECT, "Pilot"))
        sharedTeams.attach(w.manager, source.id, w.team.id)
        teams.put(w.manager, source.id, Actors.user("dev").principal, TeamRole.MEMBER)
        policies.create(
            w.manager,
            source.id,
            CreatePolicy(TeamRole.MEMBER, TypeId.DOCUMENT, "RELEASED", PolicyEffect.DENY, setOf(REVISE)),
        )

        val template = templates.saveFromContext(admin, source.id, unique("Pilot project"), null)

        assertThat(template.kind).isEqualTo(ContextKind.PROJECT)
        assertThat(template.content.sharedTeams).containsExactly(w.team.name)
        assertThat(
            template.content.policies,
        ).containsExactly(TemplatePolicy("MEMBER", "Document", "RELEASED", PolicyEffect.DENY, setOf(REVISE)))
        assertThat(template.content.members.map { it.role }).contains("MANAGER", "MEMBER")
        val copy =
            templates.createContext(
                w.manager,
                CreateContext(w.org.id, ContextKind.PROJECT, "Pilot 2"),
                template.id,
            )
        assertThat(sharedTeams.attached(w.manager, copy.id).map { it.id }).containsExactly(w.team.id)
        assertThat(teams.members(w.manager, copy.id).map { it.role }).contains(TeamRole.MEMBER)
        assertThat(
            access.permissions(
                Actors.user("x").also {
                    teams.put(w.manager, copy.id, it.principal, TeamRole.MEMBER)
                },
                AccessTarget(copy.id, TypeId.DOCUMENT, "RELEASED"),
            ),
        ).doesNotContain(REVISE)
    }
}
