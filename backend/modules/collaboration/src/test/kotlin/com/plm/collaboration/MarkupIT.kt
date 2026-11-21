package com.plm.collaboration

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.collaboration.api.DiscussionService
import com.plm.collaboration.api.MarkupKind
import com.plm.collaboration.api.MarkupService
import com.plm.collaboration.api.PostComment
import com.plm.collaboration.api.SaveMarkup
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
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

/** Markups: saved views with annotations, per version, each with its discussion (roadmap 6.10). */
@PlmIntegrationTest
internal class MarkupIT(
    @Autowired private val markups: MarkupService,
    @Autowired private val discussions: DiscussionService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val tag = UUID.randomUUID().toString().take(6)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("mk-alice-$tag"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("mk-bob-$tag"))
    private val vera = Actor(TenantId.DEFAULT, PrincipalId("mk-vera-$tag"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Markups $tag"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
            teams.put(admin, it, bob.principal, TeamRole.MEMBER)
        }
    }

    @Test
    fun `a markup keeps its view and pins on the version it was made on, and is discussed`() {
        val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, product(), "Housing"))
        val view = mapOf("camera" to mapOf("position" to listOf(1.0, 2.0, 3.0), "target" to listOf(0.0, 0.0, 0.0)))
        val pins = listOf(mapOf("at" to listOf(10.5, 0.0, -4.0), "text" to "Burr on this edge"))
        val m =
            markups.create(
                bob,
                part.id.value,
                SaveMarkup(title = "Edge finish", view = view, shapes = pins, note = "Please check"),
            )
        assertThat(m.version).isEqualTo("A.1")
        assertThat(m.kind).isEqualTo(MarkupKind.MODEL)
        assertThat(m.shapes.single()["text"]).isEqualTo("Burr on this edge")
        @Suppress("UNCHECKED_CAST")
        assertThat((m.view["camera"] as Map<String, Any?>)["position"]).isEqualTo(listOf(1.0, 2.0, 3.0))

        // Its topic is in the object's discussion, open to replies.
        val topic = discussions.comments(alice, part.id.value).single { it.id == m.topicId }
        assertThat(topic.body).contains("Edge finish").contains("A.1").contains("Please check")
        discussions.post(alice, part.id.value, PostComment("Deburred in the next version", parentId = m.topicId))

        // A new version: the markup stays on A.1, a new one goes on the new version.
        versioning.checkOut(alice, part.id)
        versioning.checkIn(alice, part.id, "Deburred")
        val m2 = markups.create(alice, part.id.value, SaveMarkup(title = "Checked"))
        assertThat(markups.list(vera.copy(principal = alice.principal), part.id.value).map { it.version to it.title })
            .containsExactly("A.1" to "Edge finish", "A.2" to "Checked")

        // Only its author changes it; a site admin can delete it, and its discussion stays.
        assertThatThrownBy { markups.update(alice, m.id, SaveMarkup(title = "Mine now")) }
            .isInstanceOf(PlmException::class.java)
        assertThat(
            markups.update(bob, m.id, SaveMarkup(title = "Edge finish (B side)", shapes = emptyList())).shapes,
        ).isEmpty()
        assertThatThrownBy { markups.delete(alice, m.id) }.isInstanceOf(PlmException::class.java)
        markups.delete(admin, m.id)
        assertThat(markups.list(alice, part.id.value).map { it.id }).containsExactly(m2.id)
        assertThat(discussions.comments(alice, part.id.value).map { it.id }).contains(m.topicId)

        // Those who can't read the object can't see or make its markups; titles are required.
        assertThatThrownBy { markups.list(vera, part.id.value) }.isInstanceOf(PlmException::class.java)
        assertThatThrownBy { markups.create(alice, part.id.value, SaveMarkup(title = " ")) }
            .isInstanceOf(PlmException::class.java)
        assertThatThrownBy {
            markups.create(alice, part.id.value, SaveMarkup(kind = MarkupKind.SHEET, title = "Page", page = 1))
        }.isInstanceOf(PlmException::class.java)
    }
}
