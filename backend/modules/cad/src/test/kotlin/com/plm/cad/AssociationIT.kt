package com.plm.cad

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.cad.api.AddDependency
import com.plm.cad.api.Associate
import com.plm.cad.api.AssociationKind
import com.plm.cad.api.AssociationService
import com.plm.cad.api.BuildRequest
import com.plm.cad.api.CadService
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

/** Part ↔ CAD association and build (roadmap 6.4). */
@PlmIntegrationTest
internal class AssociationIT(
    @Autowired private val associations: AssociationService,
    @Autowired private val cad: CadService,
    @Autowired private val bom: BomService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    @Test
    fun `a part's structure is built from its owning model, keeping lines added by hand`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Assoc ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)

        fun make(
            type: TypeId,
            name: String,
        ) = versioning.create(alice, CreateVersionedObject(type, p, name)).id
        val plateModel = make(TypeId.CAD_PART, "Plate")
        val boltModel = make(TypeId.CAD_PART, "Bolt")
        val nutModel = make(TypeId.CAD_PART, "Nut")
        val asmModel = make(TypeId.CAD_ASSEMBLY, "Gripper")
        val drawing = make(TypeId.CAD_DRAWING, "Gripper drawing")
        versioning.checkOut(alice, asmModel)
        cad.add(alice, asmModel.value, AddDependency(plateModel.value))
        repeat(4) {
            cad.add(alice, asmModel.value, AddDependency(boltModel.value))
            cad.add(alice, asmModel.value, AddDependency(nutModel.value))
        }
        versioning.checkIn(alice, asmModel)

        fun owned(
            name: String,
            model: MasterId,
        ): MasterId {
            val part = make(TypeId.PART, name)
            versioning.checkOut(alice, part)
            associations.associate(alice, part.value, Associate(model.value))
            versioning.checkIn(alice, part)
            return part
        }
        val plate = owned("Plate", plateModel)
        val bolt = owned("Bolt", boltModel)
        val label = make(TypeId.PART, "Label")
        val gripper = make(TypeId.PART, "Gripper")
        versioning.checkOut(alice, gripper)
        associations.associate(alice, gripper.value, Associate(asmModel.value))
        assertThatThrownBy {
            associations.associate(alice, gripper.value, Associate(drawing.value))
        }.hasMessageContaining("drawing")
        bom.add(alice, gripper, AddUsage(label.value, BigDecimal.ONE))

        val first = associations.build(alice, gripper.value)
        assertThat(first.lines.associate { it.part.name to (it.quantity.toInt() to it.change) })
            .isEqualTo(mapOf("Label" to (1 to "KEPT"), "Plate" to (1 to "ADDED"), "Bolt" to (4 to "ADDED")))
        assertThat(first.missing.map { it.name }).containsExactly("Nut")
        versioning.checkIn(alice, gripper)
        // The model owns one part only.
        val other = make(TypeId.PART, "Other bolt")
        versioning.checkOut(alice, other)
        assertThatThrownBy {
            associations.associate(alice, other.value, Associate(boltModel.value))
        }.hasMessageContaining("already owns")
        versioning.undoCheckOut(alice, other)
        assertThat(
            associations
                .associations(alice, boltModel.value)
                .single()
                .part.name,
        ).isEqualTo("Bolt")

        // The model changes: no plate, two bolts. Rebuilding follows it and makes the nut's part.
        versioning.checkOut(alice, asmModel)
        val deps = cad.dependencies(alice, asmModel.value, workingCopy = true)
        cad.remove(alice, asmModel.value, deps.first { it.child.id == plateModel.value }.id)
        deps.filter { it.child.id == boltModel.value }.take(2).forEach { cad.remove(alice, asmModel.value, it.id) }
        versioning.checkIn(alice, asmModel)
        versioning.checkOut(alice, gripper)
        assertThat(
            associations.associations(alice, gripper.value, workingCopy = true).single().kind,
        ).isEqualTo(AssociationKind.OWNER)
        val second = associations.build(alice, gripper.value, BuildRequest(createParts = true))
        assertThat(second.lines.associate { it.part.name to (it.quantity.toInt() to it.change) })
            .isEqualTo(
                mapOf(
                    "Label" to (1 to "KEPT"),
                    "Plate" to (1 to "REMOVED"),
                    "Bolt" to (2 to "UPDATED"),
                    "Nut" to (4 to "ADDED"),
                ),
            )
        assertThat(second.created.map { it.name }).containsExactly("Nut")
        assertThat(second.missing).isEmpty()
        versioning.checkIn(alice, gripper)
        assertThat(bom.usages(alice, gripper).map { it.child.name }).containsExactlyInAnyOrder("Label", "Bolt", "Nut")
        assertThat(bom.usages(alice, plate)).isEmpty()
        assertThat(versioning.get(alice, bolt).name).isEqualTo("Bolt")
    }
}
