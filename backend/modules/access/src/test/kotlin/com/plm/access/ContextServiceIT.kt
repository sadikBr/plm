package com.plm.access

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class ContextServiceIT(
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actors.admin

    private fun org() =
        contexts.create(
            admin,
            CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Org ${UUID.randomUUID()}"),
        )

    @Test
    fun `builds the site - organization - product hierarchy`() {
        val org = org()
        val product = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Drone X1"))

        assertThat(product.parentId).isEqualTo(org.id)
        assertThat(contexts.children(admin, org.id).map { it.name }).containsExactly("Drone X1")
        assertThat(contexts.get(admin, ContextId.DEFAULT_SITE).kind).isEqualTo(ContextKind.SITE)
    }

    @Test
    fun `only site admins create organizations, managers create inside them`() {
        val alice = Actors.user("alice")
        assertThatThrownBy {
            contexts.create(alice, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Mine"))
        }.isInstanceOf(NotFoundException::class.java) // alice cannot even see the site yet

        val org = org()
        teams.put(admin, org.id, alice.principal, TeamRole.MANAGER)
        val product = contexts.create(alice, CreateContext(org.id, ContextKind.PRODUCT, "Alice's product"))

        assertThat(teams.members(alice, product.id).single().role).isEqualTo(TeamRole.MANAGER)
        assertThatThrownBy {
            contexts.create(alice, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Mine"))
        }.isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ACCESS_DENIED") }
    }

    @Test
    fun `members cannot create sub-contexts`() {
        val bob = Actors.user("bob")
        val org = org()
        teams.put(admin, org.id, bob.principal, TeamRole.MEMBER)

        assertThatThrownBy { contexts.create(bob, CreateContext(org.id, ContextKind.PROJECT, "Bob's project")) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ACCESS_DENIED") }
    }

    @Test
    fun `contexts are visible along the team's path only`() {
        val carol = Actors.user("carol")
        val org = org()
        val p1 = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "P1"))
        contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "P2"))
        teams.put(admin, p1.id, carol.principal, TeamRole.VIEWER)

        // Ancestors are visible so carol can navigate down to P1. The sibling P2 is not.
        assertThat(contexts.get(carol, org.id).id).isEqualTo(org.id)
        assertThat(contexts.get(carol, ContextId.DEFAULT_SITE).id).isEqualTo(ContextId.DEFAULT_SITE)
        assertThat(contexts.children(carol, org.id).map { it.name }).containsExactly("P1")
        assertThat(contexts.children(admin, org.id).map { it.name }).containsExactly("P1", "P2")
    }

    @Test
    fun `enforces which kinds may nest`() {
        val org = org()
        val library = contexts.create(admin, CreateContext(org.id, ContextKind.LIBRARY, "Fasteners"))

        assertThatThrownBy { contexts.create(admin, CreateContext(ContextId.DEFAULT_SITE, ContextKind.PRODUCT, "P")) }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.code).isEqualTo("INVALID_CONTEXT_HIERARCHY")
            }
        assertThatThrownBy { contexts.create(admin, CreateContext(library.id, ContextKind.PRODUCT, "P")) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `sibling names are unique`() {
        val org = org()
        contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Same"))

        assertThatThrownBy { contexts.create(admin, CreateContext(org.id, ContextKind.PROJECT, "Same")) }
            .isInstanceOfSatisfying(ConflictException::class.java) {
                assertThat(it.code).isEqualTo("DUPLICATE_CONTEXT_NAME")
            }
    }

    @Test
    fun `search finds visible contexts by name, and path lists ancestors root first`() {
        val tag = UUID.randomUUID().toString().take(8)
        val org = contexts.create(admin, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Search $tag"))
        val mine = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Gearbox $tag"))
        contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Gear_$tag hidden"))
        val dora = Actors.user("dora").also { teams.put(admin, mine.id, it.principal, TeamRole.MEMBER) }

        assertThat(contexts.search(admin, "gear").map { it.name }).contains("Gearbox $tag", "Gear_$tag hidden")
        // Dora sees her product and, through it, its organization, but not its sibling.
        assertThat(contexts.search(dora, tag).map { it.name }).containsExactlyInAnyOrder("Search $tag", "Gearbox $tag")
        // Wildcards are literal.
        assertThat(contexts.search(admin, "Gear_$tag").map { it.name }).containsExactly("Gear_$tag hidden")
        assertThat(contexts.path(dora, mine.id).map { it.id }).containsExactly(ContextId.DEFAULT_SITE, org.id, mine.id)
    }

    @Test
    fun `contexts can be listed by kind, only the visible ones`() {
        val tag = UUID.randomUUID().toString().take(8)
        val org = contexts.create(admin, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Kinds $tag"))
        val product = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Pump $tag"))
        contexts.create(admin, CreateContext(org.id, ContextKind.LIBRARY, "Standard parts $tag"))
        val erin = Actors.user("erin").also { teams.put(admin, product.id, it.principal, TeamRole.VIEWER) }

        assertThat(contexts.search(admin, "", 500, ContextKind.LIBRARY).map { it.name }).contains("Standard parts $tag")
        assertThat(
            contexts
                .search(admin, "", 500, ContextKind.LIBRARY)
                .map {
                    it.kind
                }.toSet(),
        ).containsOnly(ContextKind.LIBRARY)
        assertThat(contexts.search(erin, "", 500, ContextKind.PRODUCT).map { it.id }).containsExactly(product.id)
        assertThat(contexts.search(erin, "", 500, ContextKind.LIBRARY)).isEmpty()
        assertThat(contexts.search(erin, "", 500)).isEmpty() // no kind, no text: nothing
    }
}
