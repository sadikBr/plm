package com.plm.bom

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.RollupService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.search.api.SearchQuery
import com.plm.search.api.SearchService
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** Roll-ups, flattened structures and structure-scoped searches (roadmap 4.11). */
@PlmIntegrationTest
internal class RollupIT(
    @Autowired private val rollups: RollupService,
    @Autowired private val bom: BomService,
    @Autowired private val search: SearchService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    @Test
    fun `mass rolls up in kilograms, quantities flatten across levels, and searches stay inside the structure`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Roll ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val word = "ru" + UUID.randomUUID().toString().take(6)

        fun part(
            name: String,
            mass: Pair<Double, String>?,
        ) = versioning
            .create(
                alice,
                CreateVersionedObject(
                    TypeId.PART,
                    p,
                    "$name $word",
                    attributes =
                        mass?.let { mapOf("mass" to mapOf("value" to it.first, "unit" to it.second)) } ?: emptyMap(),
                ),
            ).id

        fun use(
            parent: MasterId,
            vararg kids: Pair<MasterId, Int>,
        ) {
            versioning.checkOut(alice, parent)
            kids.forEach { (k, q) -> bom.add(alice, parent, AddUsage(k.value, BigDecimal(q))) }
            versioning.checkIn(alice, parent)
        }
        val top = part("Vehicle", null)
        val wheel = part("Wheel", null)
        val rim = part("Rim", 1.5 to "kg")
        val bolt = part("Bolt", 20.0 to "g")
        val nameplate = part("Nameplate", null)
        use(wheel, rim to 1, bolt to 5)
        use(top, wheel to 4, bolt to 10, nameplate to 1)

        val mass = rollups.rollup(alice, top, "mass")
        assertThat(mass.unit).isEqualTo("kg")
        // 4 × (1.5 + 5 × 0.02) + 10 × 0.02 = 6.6
        assertThat(mass.total).isEqualByComparingTo("6.6")
        assertThat(mass.missing).isEqualTo(1)
        assertThat(mass.nodes.first { it.part.name == "Wheel $word" }.rolledValue).isEqualByComparingTo("1.6")
        assertThat(mass.nodes.first { it.part.name == "Nameplate $word" }.missing).isTrue()

        val flat = rollups.flatten(alice, top)
        assertThat(flat.first { it.part.name == "Bolt $word" }.totalQuantity).isEqualByComparingTo("30")
        assertThat(flat.first { it.part.name == "Bolt $word" }.levels).containsExactly(1, 2)
        assertThat(rollups.attributes(alice, top).map { it.name }).contains("mass")

        // Searches and reports can be limited to the structure.
        val other = part("Bolt", null) // same words, outside the structure
        repeat(80) {
            val hits = search.matching(alice, SearchQuery(text = word, withinStructureOf = top.value)).ids
            if (hits.size == 4) {
                assertThat(hits).doesNotContain(top.value, other.value)
                return
            }
            Thread.sleep(150)
        }
        throw AssertionError("The scoped search never found the 4 parts")
    }
}
