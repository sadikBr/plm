package com.plm.extensions

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.extensions.api.ActionConfiguration
import com.plm.extensions.api.ActionPlacement
import com.plm.extensions.api.ActionService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class ActionServiceIT(
    @Autowired private val actions: ActionService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val manager = Actor(TenantId.DEFAULT, PrincipalId("mona-manager"))
    private val product: ContextId by lazy {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Org ${UUID.randomUUID()}"),
            )
        contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Drone")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
            teams.put(admin, it, manager.principal, TeamRole.MANAGER)
        }
    }

    private fun newPart() =
        versioning.create(
            alice,
            CreateVersionedObject(
                TypeId.PART,
                product,
                "Bracket",
                attributes =
                    mapOf(
                        "material" to "AL6061",
                    ),
            ),
        )

    @AfterEach
    fun resetRegistry() = actions.registry(admin).forEach { actions.reset(admin, it.declared.id) }

    @Test
    fun `lists an object's actions in order, explaining the disabled ones`() {
        val part = newPart()
        val available = actions.available(alice, part.id).associateBy { it.id }

        assertThat(actions.available(alice, part.id).map { it.id }).startsWith(
            "object.check-out",
            "object.check-in",
            "object.undo-check-out",
            "object.revise",
            "object.set-state",
        )
        assertThat(available["object.check-out"]!!.enabled).isTrue()
        assertThat(available["object.check-in"]!!.reason).isEqualTo("Only whoever checked it out can do this")
        assertThat(available["object.revise"]!!.reason).contains("can be revised")
        assertThat(
            available["object.rename"]!!.reason,
        ).isEqualTo("Only managers of this context can rename or renumber")
        assertThat(available).doesNotContainKey("object.change-lifecycle") // site administrators only
        assertThat(available["object.copy-link"]!!.placement).isEqualTo(ActionPlacement.OBJECT_MORE)

        versioning.checkOut(alice, part.id)
        val checkedOut = actions.available(alice, part.id).associateBy { it.id }
        assertThat(checkedOut["object.check-in"]!!.enabled).isTrue()
        assertThat(checkedOut["object.check-out"]!!.reason).isEqualTo("Checked out by you")
    }

    @Test
    fun `set state offers the allowed transitions, and overrides to site administrators`() {
        val part = newPart()
        val forManager = actions.available(manager, part.id).first { it.id == "object.set-state" }
        assertThat(forManager.options.map { it.value }).contains("UNDER_REVIEW").doesNotContain("RELEASED")
        assertThat(forManager.options.none { it.override }).isTrue()

        val forAdmin = actions.available(admin, part.id).first { it.id == "object.set-state" }
        assertThat(forAdmin.options.filter { it.override }.map { it.value }).contains("RELEASED")

        val forAlice = actions.available(alice, part.id).first { it.id == "object.set-state" }
        assertThat(forAlice.enabled).isFalse()
    }

    @Test
    fun `administrators hide, move, relabel and restrict actions`() {
        val part = newPart()
        actions.configure(admin, "object.undo-check-out", ActionConfiguration(hidden = true))
        actions.configure(
            admin,
            "object.copy-link",
            ActionConfiguration(placement = ActionPlacement.OBJECT_TOOLBAR, order = 1, label = "Share"),
        )
        actions.configure(admin, "object.revise", ActionConfiguration(states = listOf("RELEASED")))

        val inWork = actions.available(alice, part.id)
        assertThat(inWork.map { it.id }).doesNotContain("object.undo-check-out", "object.revise")
        assertThat(inWork.first().id).isEqualTo("object.copy-link")
        assertThat(inWork.first().label).isEqualTo("Share")

        versioning.setState(manager, part.id, SetState("UNDER_REVIEW"))
        versioning.setState(manager, part.id, SetState("RELEASED"))
        assertThat(actions.available(alice, part.id).map { it.id }).contains("object.revise")

        val reset = actions.reset(admin, "object.copy-link")
        assertThat(reset.configuration).isNull()
        assertThat(reset.effective.placement).isEqualTo(ActionPlacement.OBJECT_MORE)
    }

    @Test
    fun `restricting to a type includes its subtypes and excludes other types`() {
        val part = newPart()
        actions.configure(admin, "object.revise", ActionConfiguration(types = listOf("Document")))
        assertThat(actions.available(alice, part.id).map { it.id }).doesNotContain("object.revise")
        actions.configure(admin, "object.revise", ActionConfiguration(types = listOf("Part")))
        assertThat(actions.available(alice, part.id).map { it.id }).contains("object.revise")
    }

    @Test
    fun `configuration is validated and for site administrators only`() {
        assertThatThrownBy { actions.registry(alice) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy {
            actions.configure(admin, "nope", ActionConfiguration())
        }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy {
            actions.configure(
                admin,
                "object.revise",
                ActionConfiguration(types = listOf("Spaceship")),
            )
        }.isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("UNKNOWN_TYPE") }
        assertThatThrownBy {
            actions.configure(
                admin,
                "object.revise",
                ActionConfiguration(states = listOf("SHIPPED")),
            )
        }.isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("INVALID_STATE") }
    }
}
