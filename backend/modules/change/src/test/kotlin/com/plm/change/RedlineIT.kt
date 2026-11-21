package com.plm.change

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.change.api.AddChangeLink
import com.plm.change.api.AddRedline
import com.plm.change.api.ChangeRole
import com.plm.change.api.ChangeService
import com.plm.change.api.MassChange
import com.plm.change.api.RedlineOp
import com.plm.change.api.RedlineService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ValidationException
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
import java.math.BigDecimal
import java.util.UUID

/** Redlines and mass changes (roadmap 5.11). */
@PlmIntegrationTest
internal class RedlineIT(
    @Autowired private val redlines: RedlineService,
    @Autowired private val changes: ChangeService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val bom: BomService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Redlines ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun part(
        p: ContextId,
        name: String,
    ) = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id

    private fun uses(
        parent: MasterId,
        vararg children: Pair<MasterId, Int>,
    ) {
        versioning.checkOut(alice, parent)
        children.forEach { (c, q) -> bom.add(alice, parent, AddUsage(c.value, BigDecimal(q))) }
        versioning.checkIn(alice, parent)
    }

    @Test
    fun `a task's edits are applied when it is resolved, and released with its notice`() {
        val p = product()
        val wheel = part(p, "Wheel")
        val hub = part(p, "Hub")
        val bracket = part(p, "Bracket")
        val oldBolt = part(p, "Bolt M6")
        val newBolt = part(p, "Bolt M8")
        val washer = part(p, "Washer")
        uses(wheel, oldBolt to 4)
        uses(hub, oldBolt to 6, washer to 6)
        versioning.setState(admin, wheel, SetState("RELEASED", override = true))

        val cn = versioning.create(alice, CreateVersionedObject(TypeId.CHANGE_NOTICE, p, "Stronger bolts")).id.value
        val ct =
            versioning
                .create(
                    alice,
                    CreateVersionedObject(TypeId.CHANGE_TASK, p, "Update the structures"),
                ).id.value
        changes.link(alice, cn, AddChangeLink(ct, ChangeRole.TASK))
        listOf(wheel, hub, bracket).forEach { changes.link(alice, ct, AddChangeLink(it.value, ChangeRole.AFFECTED)) }

        // The same replacement everywhere it applies; the bracket doesn't use the bolt.
        val mass = redlines.mass(alice, ct, MassChange(RedlineOp.REPLACE, oldBolt.value, newBolt.value))
        assertThat(mass.created.map { it.target.id }).containsExactlyInAnyOrder(wheel.value, hub.value)
        assertThat(mass.skipped.single().reason).contains("doesn't use")
        redlines.add(alice, ct, AddRedline(RedlineOp.QUANTITY, hub.value, washer.value, quantity = BigDecimal(8)))
        redlines.add(
            alice,
            ct,
            AddRedline(RedlineOp.SET_ATTRIBUTE, wheel.value, attribute = "description", value = "M8 bolts"),
        )
        assertThatThrownBy { redlines.add(alice, ct, AddRedline(RedlineOp.REMOVE, bracket.value, washer.value)) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy {
            redlines.add(alice, ct, AddRedline(RedlineOp.SET_ATTRIBUTE, wheel.value, attribute = "nope", value = 1))
        }.hasMessageContaining("no attribute")
        // Nothing has changed yet.
        assertThat(bom.usages(alice, wheel).map { it.child.id }).containsExactly(oldBolt.value)

        // A target checked out by someone refuses the resolution, and nothing is applied.
        versioning.checkOut(alice, hub)
        versioning.setState(admin, MasterId(ct), SetState("IMPLEMENTATION", override = false))
        assertThatThrownBy { versioning.setState(admin, MasterId(ct), SetState("RESOLVED")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("checked out")
        versioning.undoCheckOut(alice, hub)
        assertThat(redlines.list(alice, ct).all { it.appliedAt == null }).isTrue()

        versioning.setState(admin, MasterId(ct), SetState("RESOLVED"))
        val w = versioning.get(alice, wheel)
        assertThat(w.latest.revision).isEqualTo("B")
        assertThat(w.latest.attributes["description"]).isEqualTo("M8 bolts")
        assertThat(bom.usages(alice, wheel).map { it.child.id }).containsExactly(newBolt.value)
        assertThat(bom.usages(alice, hub).associate { it.child.id to it.quantity.toInt() })
            .isEqualTo(mapOf(newBolt.value to 6, washer.value to 8))
        assertThat(redlines.list(alice, ct).all { it.appliedVersion != null }).isTrue()
        val resulting =
            changes
                .view(alice, cn)
                .links
                .filter { it.role == ChangeRole.RESULTING }
                .map { it.target.id }
        assertThat(resulting).containsExactlyInAnyOrder(wheel.value, hub.value)

        // Resolving the notice releases what the task produced.
        versioning.setState(admin, MasterId(cn), SetState("IMPLEMENTATION", override = true))
        versioning.setState(admin, MasterId(cn), SetState("RESOLVED"))
        assertThat(versioning.get(alice, wheel).latest.state).isEqualTo("RELEASED")
        assertThat(versioning.get(alice, hub).latest.state).isEqualTo("RELEASED")
    }
}
