package com.plm.collaboration

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.audit.api.AuditQuery
import com.plm.audit.api.AuditService
import com.plm.collaboration.api.DiscussionService
import com.plm.collaboration.api.PostComment
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.notifications.api.PersonalService
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.ShareRequest
import com.plm.versioning.api.SharingService
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.MutableClock
import plm.testing.PlmIntegrationTest
import java.time.Duration
import java.util.UUID

/** Discussions, ad hoc sharing and audit reports (roadmap 3.10). */
@PlmIntegrationTest
internal class CollaborationIT(
    @Autowired private val discussions: DiscussionService,
    @Autowired private val sharing: SharingService,
    @Autowired private val audit: AuditService,
    @Autowired private val personal: PersonalService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val directory: PrincipalDirectory,
    @Autowired private val clock: MutableClock,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val tag = UUID.randomUUID().toString().take(6)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("co-alice-$tag"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("co-bob-$tag"))
    private val vera = Actor(TenantId.DEFAULT, PrincipalId("co-vera-$tag"))

    init {
        for (a in listOf(alice, bob, vera)) {
            val name = a.principal.value
            directory.recordSignIn(SignIn(TenantId.DEFAULT, a.principal, name, name, "$name@example.com"))
        }
    }

    private fun product(): ContextId {
        val org = contexts.create(admin, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Collab $tag"))
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MANAGER)
            teams.put(admin, it, bob.principal, TeamRole.MEMBER)
        }
    }

    @Test
    fun `a shared object is readable (and editable if allowed) by someone off the team, until it expires`() {
        val p = product()
        val part = versioning.create(bob, CreateVersionedObject(TypeId.PART, p, "Bracket"))
        val other = versioning.create(bob, CreateVersionedObject(TypeId.PART, p, "Other"))
        assertThatThrownBy { versioning.get(vera, part.id) }.isInstanceOf(PlmException::class.java)

        // Bob is a member, not the context's manager, but he owns it.
        sharing.share(
            bob,
            part.id,
            ShareRequest(
                vera.principal.value,
                note = "For the supplier review",
                expiresAt = clock.instant().plus(Duration.ofDays(7)),
            ),
        )
        assertThat(versioning.get(vera, part.id).name).isEqualTo("Bracket")
        assertThat(versioning.list(vera, ObjectQuery(contextId = p)).map { it.id }).containsExactly(part.id)
        assertThat(contexts.get(vera, p).name).isEqualTo("Rover")
        assertThat(sharing.sharedWithMe(vera).single().number).isEqualTo(part.number)
        assertThat(personal.notifications(vera).first().title).contains("shared ${part.number} Bracket with you")
        assertThatThrownBy { versioning.checkOut(vera, part.id) }.isInstanceOf(PlmException::class.java)
        // Someone who neither owns it nor manages the context can't share it.
        assertThatThrownBy {
            sharing.share(vera, part.id, ShareRequest(alice.principal.value))
        }.isInstanceOf(PlmException::class.java)
        assertThat(versioning.list(vera, ObjectQuery(ids = listOf(other.id)))).isEmpty()

        sharing.share(alice, part.id, ShareRequest(vera.principal.value, modify = true))
        versioning.checkOut(vera, part.id)
        versioning.checkIn(vera, part.id, "Supplier changes")

        sharing.share(
            alice,
            part.id,
            ShareRequest(vera.principal.value, expiresAt = clock.instant().plus(Duration.ofHours(1))),
        )
        clock.advance(Duration.ofHours(2))
        assertThatThrownBy { versioning.get(vera, part.id) }.isInstanceOf(PlmException::class.java)

        sharing.unshare(alice, part.id, vera.principal.value)
        assertThat(sharing.shares(alice, part.id).map { it.principalId }).doesNotContain(vera.principal.value)
    }

    @Test
    fun `comments mention people who can read the object, and replies reach those taking part`() {
        val p = product()
        val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Hub"))
        val topic =
            discussions.post(
                alice,
                part.id.value,
                PostComment("@${bob.principal.value} can you check the bore? cc @${vera.principal.value}"),
            )
        assertThat(topic.mentions).containsExactlyInAnyOrder(bob.principal.value, vera.principal.value)
        assertThat(personal.notifications(bob).first().title).contains("mentioned you on ${part.number} Hub")
        // Vera can't read the object: she isn't told about it.
        assertThat(personal.notifications(vera)).isEmpty()
        assertThatThrownBy { discussions.comments(vera, part.id.value) }.isInstanceOf(PlmException::class.java)

        discussions.post(bob, part.id.value, PostComment("Bore is 12 mm, fine.", topic.id))
        assertThat(personal.notifications(alice).first().title).contains("replied on ${part.number} Hub")
        assertThat(personal.notifications(alice).first().link).isEqualTo("/objects/${part.id.value}?tab=discussion")

        assertThatThrownBy { discussions.edit(bob, topic.id, "hijack") }.isInstanceOf(PlmException::class.java)
        discussions.edit(alice, topic.id, "Can someone check the bore?")
        discussions.resolve(bob, topic.id, true)
        val thread = discussions.comments(bob, part.id.value)
        assertThat(thread).hasSize(2)
        assertThat(thread.first().resolved).isTrue()
        assertThat(thread.first().editedAt).isNotNull()
        discussions.delete(alice, topic.id)
        assertThat(discussions.comments(bob, part.id.value).first().body).isEmpty()
    }

    @Test
    fun `a context manager reports on what happened in their context, site admins on everything`() {
        val p = product()
        val part = versioning.create(bob, CreateVersionedObject(TypeId.PART, p, "Axle"))
        versioning.checkOut(bob, part.id)
        versioning.checkIn(bob, part.id)
        val report = audit.report(alice, AuditQuery(contextId = p.value))
        assertThat(report.total).isGreaterThanOrEqualTo(3)
        assertThat(report.byActor.first().key).isEqualTo(bob.principal.value)
        assertThat(report.byAction.map { it.key }).contains("versioning.ObjectCheckedIn.v1")
        assertThat(
            audit.report(alice, AuditQuery(contextId = p.value, action = "versioning.ObjectCheckedIn")).total,
        ).isEqualTo(1)
        assertThatThrownBy { audit.report(bob, AuditQuery(contextId = p.value)) }.isInstanceOf(PlmException::class.java)
        assertThatThrownBy { audit.report(alice, AuditQuery()) }.isInstanceOf(PlmException::class.java)
        assertThat(audit.report(admin, AuditQuery(actor = bob.principal.value)).total).isGreaterThanOrEqualTo(3)
    }
}
