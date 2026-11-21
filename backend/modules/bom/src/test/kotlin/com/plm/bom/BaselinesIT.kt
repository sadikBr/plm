package com.plm.bom

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BaselineService
import com.plm.bom.api.BomService
import com.plm.bom.api.CollectRequest
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ConfigSpecType
import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.DocumentLinkService
import com.plm.bom.api.NewBaseline
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** Baselines and collecting (roadmap 4.10). */
@PlmIntegrationTest
internal class BaselinesIT(
    @Autowired private val baselines: BaselineService,
    @Autowired private val bom: BomService,
    @Autowired private val links: DocumentLinkService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))

    @Test
    fun `a collected structure becomes a baseline that keeps reading as it was`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Base ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)

        fun part(name: String) = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id
        val top = part("Top")
        val leaf = part("Leaf")
        val doc = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, p, "Top drawing")).id
        versioning.checkOut(alice, top)
        bom.add(alice, top, AddUsage(leaf.value, BigDecimal("2")))
        links.link(alice, top, DocumentLinkKind.DESCRIBED_BY, doc.value)
        versioning.checkIn(alice, top)

        val collected = baselines.collect(alice, CollectRequest(top.value))
        assertThat(collected.map { it.item.name to it.relation })
            .containsExactly("Top" to "TOP", "Leaf" to "STRUCTURE", "Top drawing" to "DESCRIBED_BY")
        val baseline = baselines.create(alice, NewBaseline("Shipped to customer", contextId = p.value))
        assertThat(baseline.number).startsWith("BL-")
        baselines.add(alice, baseline.id, collected.map { it.item.iterationId!! })
        assertThat(baselines.members(alice, baseline.id)).hasSize(3)
        assertThat(baselines.list(alice, MasterId(leaf.value)).map { it.id }).contains(baseline.id)

        // The structure moves on; the baseline still reads as it was.
        versioning.checkOut(alice, top)
        val line = bom.usages(alice, top, workingCopy = true).single()
        bom.update(
            alice,
            top,
            line.id,
            com.plm.bom.api
                .UpdateUsage(BigDecimal("5"), "each", 10),
        )
        bom.add(alice, top, AddUsage(part("Extra").value))
        versioning.checkIn(alice, top)
        val asBaselined =
            bom.structure(
                alice,
                top,
                ConfigSpec(ConfigSpecType.BASELINE, baselineId = baseline.id),
                depth = 5,
            )
        assertThat(asBaselined.rootVersion).isEqualTo("A.2")
        assertThat(asBaselined.nodes.map { it.line.quantity }).containsExactly(BigDecimal("2"))

        // Bob can't see it; frozen, it can't change.
        assertThatThrownBy { baselines.get(bob, baseline.id) }.hasMessageContaining("no such baseline")
        baselines.freeze(alice, baseline.id)
        assertThatThrownBy { baselines.remove(alice, baseline.id, listOf(leaf.value)) }.hasMessageContaining("frozen")
        assertThatThrownBy { baselines.delete(alice, baseline.id) }.hasMessageContaining("frozen")
    }
}
