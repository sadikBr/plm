package com.plm.bom

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.UpdateUsage
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** Product structure: usage links (roadmap 4.1). */
@PlmIntegrationTest
internal class StructureIT(
    @Autowired private val bom: BomService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val organizing: OrganizingService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Bom ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun part(
        p: ContextId,
        name: String,
        type: TypeId = TypeId.PART,
    ): MasterId = versioning.create(alice, CreateVersionedObject(type, p, name)).id

    @Test
    fun `lines are edited on the working copy, frozen at check-in and carried to the next iteration`() {
        val p = product()
        val axle = part(p, "Axle assembly")
        val shaft = part(p, "Shaft")
        val bearing = part(p, "Bearing")
        versioning.checkOut(alice, axle)
        val l1 = bom.add(alice, axle, AddUsage(shaft.value))
        val l2 = bom.add(alice, axle, AddUsage(bearing.value, BigDecimal("2"), findNumber = "3"))
        assertThat(listOf(l1.lineNumber, l2.lineNumber)).containsExactly(10, 20)
        assertThat(l1.unit).isEqualTo("each")
        assertThat(l2.child.number).isEqualTo(versioning.get(alice, bearing).number)
        // Not visible in the latest until check-in.
        assertThat(bom.usages(alice, axle)).isEmpty()
        assertThat(bom.usages(alice, axle, workingCopy = true)).hasSize(2)
        val a1 =
            versioning
                .checkIn(alice, axle)
                .latest.id.value
        assertThat(bom.usages(alice, axle).map { it.quantity }).containsExactly(BigDecimal.ONE, BigDecimal("2"))

        versioning.checkOut(alice, axle)
        val wc = bom.usages(alice, axle, workingCopy = true)
        assertThat(wc).hasSize(2)
        bom.update(alice, axle, wc[1].id, UpdateUsage(BigDecimal("4"), "each", 20, "3"))
        bom.remove(alice, axle, wc[0].id)
        versioning.checkIn(alice, axle)
        assertThat(bom.usages(alice, axle).single().quantity).isEqualTo(BigDecimal("4"))
        // The first iteration is unchanged.
        assertThat(bom.usages(alice, axle, iterationId = a1)).hasSize(2)

        // An undone check-out drops its edits.
        versioning.checkOut(alice, axle)
        bom.add(alice, axle, AddUsage(shaft.value))
        versioning.undoCheckOut(alice, axle)
        assertThat(bom.usages(alice, axle)).hasSize(1)
    }

    @Test
    fun `only parts, no cycles, only the person who checked it out, and used parts stay`() {
        val p = product()
        val top = part(p, "Top")
        val mid = part(p, "Mid")
        val doc = part(p, "Spec", TypeId.DOCUMENT)
        assertThatThrownBy {
            bom.add(alice, top, AddUsage(mid.value))
        }.isInstanceOf(PlmException::class.java) // not checked out
        versioning.checkOut(alice, top)
        assertThatThrownBy { bom.add(alice, top, AddUsage(doc.value)) }.hasMessageContaining("Only parts")
        assertThatThrownBy { bom.add(alice, top, AddUsage(top.value)) }.hasMessageContaining("already uses")
        assertThatThrownBy {
            bom.add(
                alice,
                top,
                AddUsage(mid.value, BigDecimal.ZERO),
            )
        }.hasMessageContaining("more than 0")
        assertThatThrownBy {
            bom.add(
                alice,
                top,
                AddUsage(mid.value, unit = "parsec"),
            )
        }.hasMessageContaining("unit of use")
        bom.add(alice, top, AddUsage(mid.value))
        versioning.checkIn(alice, top)
        versioning.checkOut(alice, mid)
        assertThatThrownBy { bom.add(alice, mid, AddUsage(top.value)) }.hasMessageContaining("already uses")
        versioning.undoCheckOut(alice, mid)
        // Bob can't read the product: he gets nothing.
        assertThatThrownBy { bom.usages(bob, top) }.isInstanceOf(PlmException::class.java)
        // Mid is used: it can't be deleted.
        assertThatThrownBy { organizing.delete(admin, mid) }.hasMessageContaining("used in the structure")
    }

    @Test
    fun `children the reader may not read stay as lines without details`() {
        val p = product()
        val other =
            contexts
                .create(
                    admin,
                    CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Secret ${UUID.randomUUID()}"),
                ).let {
                    contexts.create(admin, CreateContext(it.id, ContextKind.LIBRARY, "Parts")).id
                }
        val secret = versioning.create(admin, CreateVersionedObject(TypeId.PART, other, "Secret sensor")).id
        val top = versioning.create(admin, CreateVersionedObject(TypeId.PART, p, "Top")).id
        versioning.checkOut(admin, top)
        bom.add(admin, top, AddUsage(secret.value))
        versioning.checkIn(admin, top)
        versioning.setState(admin, top, SetState("RELEASED", override = true))
        val line = bom.usages(alice, top).single()
        assertThat(line.child.readable).isFalse()
        assertThat(line.child.number).isNull()
        assertThat(
            bom
                .usages(admin, top)
                .single()
                .child.name,
        ).isEqualTo("Secret sensor")
    }

    @Test
    fun `lines are replaced, reordered and pasted from another structure`() {
        val p = product()
        val a = part(p, "Assembly A")
        val b = part(p, "Assembly B")
        val (x, y, z) = listOf(part(p, "X"), part(p, "Y"), part(p, "Z"))
        versioning.checkOut(alice, a)
        bom.add(alice, a, AddUsage(x.value, BigDecimal("3"), findNumber = "7"))
        val second = bom.add(alice, a, AddUsage(y.value))
        val replaced = bom.replace(alice, a, second.id, z.value)
        assertThat(replaced.child.id).isEqualTo(z.value)
        assertThat(replaced.lineNumber).isEqualTo(20)
        val reordered = bom.reorder(alice, a, bom.usages(alice, a, workingCopy = true).map { it.id }.reversed())
        assertThat(reordered.map { it.child.id to it.lineNumber }).containsExactly(z.value to 10, x.value to 20)
        assertThatThrownBy { bom.reorder(alice, a, listOf(replaced.id)) }.hasMessageContaining("every line")
        versioning.checkIn(alice, a)

        versioning.checkOut(alice, b)
        bom.add(alice, b, AddUsage(y.value))
        val pasted = bom.paste(alice, b, bom.usages(alice, a).map { it.id })
        assertThat(pasted.map { it.lineNumber }).containsExactly(20, 30)
        assertThat(pasted.first { it.child.id == x.value }.quantity).isEqualTo(BigDecimal("3"))
        assertThat(pasted.first { it.child.id == x.value }.findNumber).isEqualTo("7")
        versioning.checkIn(alice, b)
        // Pasting B's lines into one of its own children would loop... and A under Z is fine.
        versioning.checkOut(alice, z)
        assertThatThrownBy {
            bom.replace(alice, z, bom.add(alice, z, AddUsage(y.value)).id, a.value)
        }.hasMessageContaining("already uses")
    }

    @Test
    fun `reference designators name each occurrence and match the quantity`() {
        val p = product()
        val board = part(p, "Board")
        val resistor = part(p, "Resistor 10k")
        val cap = part(p, "Capacitor 1uF")
        versioning.checkOut(alice, board)
        val r = bom.add(alice, board, AddUsage(resistor.value, BigDecimal("5"), designators = "R1-R4, R7"))
        assertThat(r.designators).containsExactly("R1", "R2", "R3", "R4", "R7")
        assertThatThrownBy { bom.add(alice, board, AddUsage(cap.value, BigDecimal("2"), designators = "C1")) }
            .hasMessageContaining("1 designators (C1) for a quantity of 2")
        assertThatThrownBy { bom.add(alice, board, AddUsage(cap.value, BigDecimal("2"), designators = "C1, R7")) }
            .hasMessageContaining("Already used on another line: R7")
        assertThatThrownBy {
            bom.add(
                alice,
                board,
                AddUsage(cap.value, BigDecimal("0.5"), unit = "kg", designators = "C1"),
            )
        }.hasMessageContaining("whole quantity")
        bom.add(alice, board, AddUsage(cap.value, BigDecimal("3"), designators = "C1 - C3"))
        bom.update(alice, board, r.id, UpdateUsage(BigDecimal("2"), "each", 10, null, null, "R1, R2"))
        versioning.checkIn(alice, board)
        // Carried to the next iteration.
        versioning.checkOut(alice, board)
        assertThat(bom.usages(alice, board, workingCopy = true).map { it.designators })
            .containsExactly(listOf("R1", "R2"), listOf("C1", "C2", "C3"))
    }
}
