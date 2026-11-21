package com.plm.cad

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.cad.api.AddDependency
import com.plm.cad.api.CadKind
import com.plm.cad.api.CadService
import com.plm.cad.api.DependencyKind
import com.plm.cad.api.Transforms
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

/** CAD documents and their dependencies (roadmap 6.1). */
@PlmIntegrationTest
internal class CadIT(
    @Autowired private val cad: CadService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val organizing: com.plm.versioning.api.OrganizingService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "CAD ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    @Test
    fun `assemblies place their components, drawings show models, and both follow versions`() {
        val p = product()

        fun make(
            type: TypeId,
            name: String,
        ) = versioning.create(alice, CreateVersionedObject(type, p, name)).id
        val plate = make(TypeId.CAD_PART, "Plate")
        val bolt = make(TypeId.CAD_PART, "Bolt")
        val bracket = make(TypeId.CAD_ASSEMBLY, "Bracket")
        val top = make(TypeId.CAD_ASSEMBLY, "Gripper")
        val drawing = make(TypeId.CAD_DRAWING, "Gripper drawing")
        assertThat(versioning.get(alice, plate).number).startsWith("CAD-")
        assertThat(cad.kindOf(alice, top.value)).isEqualTo(CadKind.ASSEMBLY)

        fun build(
            parent: MasterId,
            vararg deps: AddDependency,
        ) {
            versioning.checkOut(alice, parent)
            deps.forEach { cad.add(alice, parent.value, it) }
            versioning.checkIn(alice, parent)
        }
        build(
            bracket,
            AddDependency(bolt.value, occurrence = "BOLT:1", transform = Transforms.translation(10.0, 0.0, 0.0)),
            AddDependency(bolt.value, occurrence = "BOLT:2", transform = Transforms.translation(-10.0, 0.0, 0.0)),
        )
        build(
            top,
            AddDependency(plate.value),
            AddDependency(bracket.value, transform = Transforms.translation(0.0, 0.0, 50.0)),
        )
        build(drawing, AddDependency(top.value, DependencyKind.DRAWING))

        val tree = cad.tree(alice, top.value)
        assertThat(tree.map { it.depth to it.model.name }).containsExactly(
            0 to "Gripper",
            1 to "Plate",
            1 to "Bracket",
            2 to "Bolt",
            2 to "Bolt",
        )
        // Each bolt is placed in the gripper: bracket's placement, then its own.
        assertThat(tree.filter { it.model.name == "Bolt" }.map { it.worldTransform!!.subList(12, 15) })
            .containsExactly(listOf(10.0, 0.0, 50.0), listOf(-10.0, 0.0, 50.0))
        assertThat(tree.first { it.model.name == "Bracket" }.model.hasDependencies).isTrue()
        assertThat(cad.whereUsed(alice, bolt.value).map { it.parent.name to it.occurrence })
            .containsExactlyInAnyOrder("Bracket" to "BOLT:1", "Bracket" to "BOLT:2")
        assertThat(cad.whereUsed(alice, top.value).single().kind).isEqualTo(DependencyKind.DRAWING)

        // The rules: what may depend on what, no cycles, only on the working copy.
        versioning.checkOut(alice, bracket)
        assertThatThrownBy {
            cad.add(
                alice,
                bracket.value,
                AddDependency(top.value),
            )
        }.hasMessageContaining("depends on")
        assertThatThrownBy {
            cad.add(
                alice,
                bracket.value,
                AddDependency(drawing.value),
            )
        }.hasMessageContaining("drawing")
        assertThatThrownBy { cad.add(alice, bracket.value, AddDependency(plate.value, DependencyKind.DRAWING)) }
            .hasMessageContaining("Only drawings")
        versioning.undoCheckOut(alice, bracket)
        assertThatThrownBy { cad.add(alice, top.value, AddDependency(bolt.value)) }.hasMessageContaining("checked out")
        assertThatThrownBy { organizing.delete(admin, bolt) }.hasMessageContaining("depend")

        // A new revision keeps its components.
        versioning.setState(admin, top, SetState("RELEASED", override = true))
        versioning.revise(alice, top)
        assertThat(versioning.get(alice, top).latest.version).isEqualTo("B.1")
        assertThat(cad.dependencies(alice, top.value).map { it.child.name }).containsExactly("Plate", "Bracket")
    }
}
