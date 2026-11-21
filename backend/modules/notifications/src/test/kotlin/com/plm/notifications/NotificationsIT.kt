package com.plm.notifications

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.jobs.api.JobService
import com.plm.jobs.api.SubmitJob
import com.plm.notifications.api.FavoriteKind
import com.plm.notifications.api.MailPreference
import com.plm.notifications.api.MailService
import com.plm.notifications.api.NotificationSettings
import com.plm.notifications.api.PersonalService
import com.plm.notifications.api.SubscriptionKind
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Favorites, recent items, subscriptions and notifications (roadmap 3.9). */
@PlmIntegrationTest
internal class NotificationsIT(
    @Autowired private val personal: PersonalService,
    @Autowired private val mail: MailService,
    @Autowired private val jobs: JobService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val directory: PrincipalDirectory,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("nt-alice-${UUID.randomUUID().toString().take(6)}"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("nt-bob-${UUID.randomUUID().toString().take(6)}"))
    private val carol = Actor(TenantId.DEFAULT, PrincipalId("nt-carol-${UUID.randomUUID().toString().take(6)}"))

    init {
        for (a in listOf(alice, bob, carol)) {
            val name = a.principal.value
            directory.recordSignIn(
                SignIn(TenantId.DEFAULT, a.principal, name, name.substringBeforeLast('-'), "$name@example.com"),
            )
        }
    }

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Notices ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
            teams.put(admin, it, bob.principal, TeamRole.MEMBER)
        }
    }

    @Test
    fun `favorites and recent items are personal and only show what is still readable`() {
        val p = product()
        val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Hub"))
        personal.addFavorite(alice, FavoriteKind.OBJECT, part.id.value, "  Hub  ")
        personal.addFavorite(alice, FavoriteKind.CONTEXT, p.value, "Rover")
        assertThat(personal.favorites(alice).map { it.label }).containsExactly("Hub", "Rover")
        assertThat(personal.favorites(bob)).isEmpty()
        personal.removeFavorite(alice, FavoriteKind.CONTEXT, p.value)
        assertThat(personal.favorites(alice)).hasSize(1)

        personal.visited(alice, part.id.value)
        personal.visited(carol, part.id.value)
        assertThat(personal.recent(alice).single().number).isEqualTo(part.number)
        // Carol isn't on the team: what she can't read isn't listed.
        assertThat(personal.recent(carol)).isEmpty()
    }

    @Test
    fun `subscribers who may read the object hear about changes, the person making them doesn't`() {
        val p = product()
        val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Axle"))
        personal.subscribe(alice, SubscriptionKind.OBJECT, part.id.value, part.number)
        personal.subscribe(bob, SubscriptionKind.CONTEXT, p.value, "Rover")
        personal.subscribe(carol, SubscriptionKind.CONTEXT, p.value, "Rover")
        personal.saveSettings(alice, NotificationSettings(MailPreference.IMMEDIATE))

        versioning.checkOut(bob, part.id)
        versioning.checkIn(bob, part.id, "Longer")

        val inbox = personal.notifications(alice)
        assertThat(inbox.map { it.title }).contains("${part.number} Axle was checked in as A.2: Longer")
        assertThat(inbox.first().link).isEqualTo("/objects/${part.id.value}")
        assertThat(inbox.first().body).isEqualTo("By nt-bob")
        assertThat(personal.unreadCount(alice)).isEqualTo(2)
        // Bob made the change; Carol may not read it.
        assertThat(personal.notifications(bob)).isEmpty()
        assertThat(personal.notifications(carol)).isEmpty()
        // Alice asked for immediate mail.
        assertThat(
            mail.recent(TenantId.DEFAULT, 20).filter {
                it.to == listOf("${alice.principal.value}@example.com")
            },
        ).hasSize(2)

        personal.markRead(alice, listOf(inbox.first().id))
        assertThat(personal.unreadCount(alice)).isEqualTo(1)
        personal.markRead(alice, emptyList())
        assertThat(personal.unreadCount(alice)).isZero()

        personal.unsubscribe(alice, SubscriptionKind.OBJECT, part.id.value)
        versioning.checkOut(bob, part.id)
        assertThat(personal.notifications(alice)).hasSize(2)
    }

    @Test
    fun `whoever submitted a background job hears when it ends`() {
        val job = jobs.submit(admin, SubmitJob("system.test", mapOf("steps" to 1, "stepMillis" to 0), "Quick test"))
        var last: Throwable? = null
        repeat(100) {
            try {
                assertThat(personal.notifications(admin).map { it.title }).contains("Finished: Quick test")
                return
            } catch (e: AssertionError) {
                last = e
                Thread.sleep(150)
            }
        }
        throw AssertionError("No notice for job ${job.id}", last)
    }
}
