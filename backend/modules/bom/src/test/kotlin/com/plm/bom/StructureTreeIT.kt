package com.plm.bom

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ConfigSpecType
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.MutableClock
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/** Configuration specifications and multi-level structures (roadmap 4.2, 4.3). */
@PlmIntegrationTest
internal class StructureTreeIT(
    @Autowired private val bom: BomService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val clock: MutableClock,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Tree ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun part(
        p: ContextId,
        name: String,
    ): MasterId = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id

    private fun use(
        parent: MasterId,
        vararg children: Pair<MasterId, Int>,
    ) {
        versioning.checkOut(alice, parent)
        children.forEach { (c, q) -> bom.add(alice, parent, AddUsage(c.value, BigDecimal(q))) }
        versioning.checkIn(alice, parent)
    }

    @Test
    fun `each specification picks its own iterations, all the way down`() {
        val p = product()
        val top = part(p, "Top")
        val mid = part(p, "Mid")
        val leaf = part(p, "Leaf")
        val extra = part(p, "Extra")
        use(mid, leaf to 2)
        use(top, mid to 1)
        versioning.setState(admin, mid, SetState("RELEASED", override = true))
        versioning.setState(admin, top, SetState("RELEASED", override = true))
        val beforeChange = clock.instant()
        clock.advance(Duration.ofMinutes(5))
        versioning.revise(alice, mid)
        versioning.checkOut(alice, mid)
        bom.add(alice, mid, AddUsage(extra.value))
        versioning.checkIn(alice, mid)

        val latest = bom.structure(alice, top, ConfigSpec.LATEST, depth = 5)
        assertThat(latest.nodes.map { it.path to it.level }).containsExactly("10" to 1, "10.10" to 2, "10.20" to 2)
        assertThat(
            latest.nodes
                .first()
                .line.child.version,
        ).isEqualTo("B.2")

        val released = bom.structure(alice, top, ConfigSpec.RELEASED, depth = 5)
        assertThat(released.nodes.map { it.path }).containsExactly("10", "10.10")
        assertThat(
            released.nodes
                .first()
                .line.child.version,
        ).isEqualTo("A.2")
        assertThat(released.rootVersion).isEqualTo("A.2")

        val asOf = bom.structure(alice, top, ConfigSpec(ConfigSpecType.AS_OF, asOf = beforeChange), depth = 5)
        assertThat(asOf.nodes.map { it.path }).containsExactly("10", "10.10")

        // One level only, and the leaf has nothing under it.
        assertThat(bom.structure(alice, top, ConfigSpec.LATEST, depth = 1).nodes).hasSize(1)
        assertThat(
            latest.nodes
                .last()
                .line.child.hasChildren,
        ).isFalse()

        // Leaf was never released: under "released", its line stays, with no iteration.
        val unresolved =
            bom
                .structure(alice, mid, ConfigSpec.RELEASED)
                .nodes
                .single()
                .line.child
        assertThat(unresolved.readable).isTrue()
        assertThat(unresolved.iterationId).isNull()
        assertThat(unresolved.hasChildren).isFalse()
    }

    @Test
    fun `a ten-level structure of about 50k lines expands in under two seconds with access applied`() {
        val p = product()
        // Two assemblies per level, each using both of the next level's plus 24 leaves.
        val leaves = (1..48).map { part(p, "Leaf $it") }
        var below: List<MasterId> = emptyList()
        for (level in 10 downTo 1) {
            val here = (1..2).map { part(p, "Level $level assembly $it") }
            here.forEachIndexed { i, a ->
                val kids = below.map { it to 1 } + leaves.subList(i * 24, i * 24 + 24).map { it to 2 }
                use(a, *kids.toTypedArray())
            }
            below = here
        }
        val top = part(p, "Vehicle")
        use(top, *below.map { it to 1 }.toTypedArray())
        bom.structure(alice, top, ConfigSpec.LATEST, depth = 50) // warm up
        val result = bom.structure(alice, top, ConfigSpec.LATEST, depth = 50)
        assertThat(result.nodes.size).isGreaterThan(45_000)
        assertThat(result.nodes.maxOf { it.level }).isEqualTo(11)
        assertThat(result.truncated).isFalse()
        assertThat(result.elapsedMs).isLessThan(2_000)
    }
}
