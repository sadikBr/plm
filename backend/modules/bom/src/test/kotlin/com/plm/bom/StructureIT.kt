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
}
