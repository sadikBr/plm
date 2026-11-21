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
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** One-off versions beside the main line (roadmap 7.10). */
@PlmIntegrationTest
internal class OneOffIT(
    @Autowired private val bom: BomService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    @Test
    fun `a one-off for an order changes nothing on the main line`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "One ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val part = { name: String -> versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id }
        val rover = part("Rover")
        val (wheel, seat, special) = listOf(part("Wheel"), part("Seat"), part("Customer seat"))
        versioning.checkOut(alice, rover)
        bom.add(alice, rover, AddUsage(wheel.value))
        val seatLine = bom.add(alice, rover, AddUsage(seat.value))
        versioning.checkIn(alice, rover)

        // Only from a version that can't be changed any more.
        assertThatThrownBy {
            versioning.makeOneOff(alice, rover, forWhat = "Order 4471")
        }.hasMessageContaining("change it directly")
        versioning.setState(admin, rover, SetState("RELEASED", override = true))
        assertThatThrownBy {
            versioning.makeOneOff(alice, rover, forWhat = " ")
        }.hasMessageContaining("Say what the one-off is for")

        val wc = versioning.makeOneOff(alice, rover, forWhat = "Order 4471, Lunar Logistics")
        assertThat(wc.version).isEqualTo("A.2-1")
        assertThat(wc.oneOffFor).isEqualTo("Order 4471, Lunar Logistics")
        // It's edited like any working copy: its structure came from its base.
        val wcSeat = bom.usages(alice, rover, workingCopy = true).single { it.child.id == seat.value }
        assertThat(wcSeat.id).isNotEqualTo(seatLine.id)
        bom.remove(alice, rover, wcSeat.id)
        bom.add(alice, rover, AddUsage(special.value))
        assertThatThrownBy {
            versioning.makeOneOff(alice, rover, forWhat = "Another")
        }.hasMessageContaining("already checked out")
        val after = versioning.checkIn(alice, rover, "Customer seat for order 4471")

        // The main line hasn't moved: latest, released and the history's main entries.
        assertThat(after.latest.version).isEqualTo("A.2")
        assertThat(versioning.get(alice, rover).latest.version).isEqualTo("A.2")
        val names = { spec: ConfigSpec -> bom.structure(alice, rover, spec).nodes.map { it.line.child.name } }
        assertThat(names(ConfigSpec.LATEST)).containsExactly("Wheel", "Seat")
        assertThat(names(ConfigSpec.RELEASED)).containsExactly("Wheel", "Seat")
        // Asked for by its iteration, the one-off has its own structure.
        val oneOff = versioning.history(alice, rover).single { it.oneOff == 1 }
        assertThat(oneOff.state).isEqualTo("RELEASED")
        assertThat(oneOff.basedOnId).isEqualTo(versioning.get(alice, rover).latest.id)
        assertThat(bom.structure(alice, rover, rootIterationId = oneOff.id.value).nodes.map { it.line.child.name })
            .containsExactly("Wheel", "Customer seat")

        // More one-offs number on; the main line still revises as before.
        assertThat(versioning.makeOneOff(alice, rover, forWhat = "Unit 17 repair").version).isEqualTo("A.2-2")
        versioning.undoCheckOut(alice, rover)
        assertThat(versioning.revise(alice, rover).latest.version).isEqualTo("B.1")
        assertThat(versioning.history(alice, MasterId(rover.value)).map { it.version }).contains("A.2", "A.2-1", "B.1")
    }
}
