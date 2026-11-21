package com.plm.cad

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.cad.api.PmiAnnotation
import com.plm.cad.api.PmiCamera
import com.plm.cad.api.PmiKind
import com.plm.cad.api.PmiService
import com.plm.cad.api.PmiView
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Model-based definition: PMI views and annotations (roadmap 6.12). */
@PlmIntegrationTest
internal class PmiIT(
    @Autowired private val pmi: PmiService,
    @Autowired private val content: ContentService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "PMI ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    @Test
    fun `PMI comes from a sidecar file at check-in, is edited on the working copy and follows versions`() {
        val p = product()
        val housing = versioning.create(alice, CreateVersionedObject(TypeId.CAD_PART, p, "Housing")).id
        val sidecar =
            """
            {"views": [{"id": "v1", "name": "Bore", "camera": {"position": [200, 150, 300], "target": [0, 0, 0]},
              "annotations": [
                {"id": "a1", "kind": "DIMENSION", "text": "Ø22 H7", "at": [40, 0, -75], "label": [60, 30, -75]},
                {"id": "a2", "kind": "DATUM", "text": "A", "at": [0, -20, 0]}
              ]}],
             "generator": "export script 1.2"}
            """.trimIndent()
        versioning.checkOut(alice, housing)
        content.store(
            alice,
            housing,
            "housing.pmi.json",
            "application/json",
            sidecar.toByteArray(),
            ContentRole.ATTACHMENT,
        )
        versioning.checkIn(alice, housing, "With PMI")

        val read = pmi.get(alice, housing.value)
        assertThat(read.version).isEqualTo("A.2")
        assertThat(read.source).isEqualTo("housing.pmi.json")
        assertThat(
            read.views
                .single()
                .annotations
                .map { it.text },
        ).containsExactly("Ø22 H7", "A")
        assertThat(
            read.views
                .single()
                .camera!!
                .position,
        ).containsExactly(200.0, 150.0, 300.0)

        // Authored in the viewer: on the working copy only, until checked in; A.2 keeps its own.
        assertThatThrownBy { pmi.put(alice, housing.value, emptyList()) }.hasMessageContaining("checked out")
        versioning.checkOut(alice, housing)
        assertThat(pmi.get(alice, housing.value, workingCopy = true).views).hasSize(1)
        val finish = PmiAnnotation("a3", PmiKind.SURFACE_FINISH, "Ra 1.6", listOf(40.0, 0.0, -75.0))
        val views =
            read.views.map { it.copy(annotations = it.annotations + finish) } +
                PmiView("v2", "Notes", PmiCamera(listOf(0.0, 0.0, 500.0), listOf(0.0, 0.0, 0.0)))
        pmi.put(alice, housing.value, views)
        assertThat(
            pmi
                .get(alice, housing.value)
                .views
                .single()
                .annotations,
        ).hasSize(2)
        versioning.checkIn(alice, housing, "Finish")
        val after = pmi.get(alice, housing.value)
        assertThat(after.version).isEqualTo("A.3")
        assertThat(after.source).isNull()
        assertThat(after.views.map { it.name }).containsExactly("Bore", "Notes")
        // The sidecar came along with the files but isn't read again: it's not new in A.3.
        assertThat(
            after.views
                .first()
                .annotations
                .map { it.text },
        ).contains("Ra 1.6")

        // And into the next revision.
        versioning.setState(admin, housing, SetState("RELEASED", override = true))
        versioning.revise(alice, housing)
        pmi.get(alice, housing.value).let {
            assertThat(it.version).startsWith("B.")
            assertThat(it.views).hasSize(2)
        }
        versioning.checkOut(alice, housing)

        // Nonsense is refused.
        assertThatThrownBy {
            pmi.put(
                alice,
                housing.value,
                listOf(PmiView("v", "V", annotations = listOf(finish.copy(at = listOf(1.0, 2.0))))),
            )
        }.hasMessageContaining("three finite numbers")
        assertThatThrownBy {
            pmi.put(alice, housing.value, listOf(PmiView("v", "V"), PmiView("v", "W")))
        }.hasMessageContaining("Two views")
    }
}
