package com.plm.cad

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.cad.api.AddDependency
import com.plm.cad.api.CadService
import com.plm.cad.api.FamilyColumn
import com.plm.cad.api.FamilyService
import com.plm.cad.api.NewInstance
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Family tables (roadmap 6.3). */
@PlmIntegrationTest
internal class FamilyIT(
    @Autowired private val families: FamilyService,
    @Autowired private val cad: CadService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    @Test
    fun `a generic bolt has instances, used in assemblies and kept in new versions`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Family ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val bolt =
            versioning
                .create(
                    alice,
                    CreateVersionedObject(
                        TypeId.CAD_PART,
                        p,
                        "Hex bolt",
                        attributes =
                            mapOf(
                                "authoringApplication" to "Creo 10",
                            ),
                    ),
                ).id
        versioning.checkOut(alice, bolt)
        assertThatThrownBy {
            families.addInstance(alice, bolt.value, NewInstance("M8x30"))
        }.hasMessageContaining("columns first")
        families.setColumns(
            alice,
            bolt.value,
            listOf(FamilyColumn("diameter", "Diameter", "mm"), FamilyColumn("length", "Length", "mm")),
        )
        families.addInstance(alice, bolt.value, NewInstance("Hex bolt M8x30", mapOf("diameter" to 8, "length" to 30)))
        val table =
            families.addInstance(
                alice,
                bolt.value,
                NewInstance(
                    "Hex bolt M8x40",
                    mapOf(
                        "diameter" to 8,
                        "length" to 40,
                    ),
                ),
            )
        assertThatThrownBy { families.addInstance(alice, bolt.value, NewInstance("X", mapOf("pitch" to 1.25))) }
            .hasMessageContaining("pitch")
        versioning.checkIn(alice, bolt)
        val m8x30 = table.instances.first().model
        assertThat(
            versioning
                .get(
                    alice,
                    com.plm.versioning.api
                        .MasterId(m8x30.id),
                ).latest.attributes["authoringApplication"],
        ).isEqualTo("Creo 10")

        // An instance knows its family; the table is the generic's latest version's.
        val membership = families.membership(alice, m8x30.id)
        assertThat(membership.role).isEqualTo("INSTANCE")
        assertThat(membership.table!!.instances.map { it.values["length"] }).containsExactly(30, 40)
        assertThat(families.membership(alice, bolt.value).role).isEqualTo("GENERIC")

        // Instances are models like any other in assemblies.
        val asm = versioning.create(alice, CreateVersionedObject(TypeId.CAD_ASSEMBLY, p, "Bracket")).id
        versioning.checkOut(alice, asm)
        cad.add(alice, asm.value, AddDependency(m8x30.id))
        versioning.checkIn(alice, asm)
        assertThat(
            cad
                .whereUsed(alice, m8x30.id)
                .single()
                .parent.name,
        ).isEqualTo("Bracket")

        // The next version keeps the table; changing it there leaves the old one as it was.
        versioning.checkOut(alice, bolt)
        families.setValues(alice, bolt.value, m8x30.id, mapOf("diameter" to 8, "length" to 35))
        versioning.checkIn(alice, bolt)
        assertThat(
            families
                .table(alice, bolt.value)
                .instances
                .first()
                .values["length"],
        ).isEqualTo(35)
        assertThat(families.table(alice, bolt.value).genericVersion).isEqualTo("A.3")
    }
}
