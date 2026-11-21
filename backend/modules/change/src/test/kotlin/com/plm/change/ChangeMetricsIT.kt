package com.plm.change

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.change.api.AddChangeLink
import com.plm.change.api.ChangeKind
import com.plm.change.api.ChangeMetricsService
import com.plm.change.api.ChangeRole
import com.plm.change.api.ChangeService
import com.plm.change.api.SetPlan
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Change dashboards (roadmap 5.12). */
@PlmIntegrationTest
internal class ChangeMetricsIT(
    @Autowired private val metrics: ChangeMetricsService,
    @Autowired private val changes: ChangeService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val directory: com.plm.identity.api.PrincipalDirectory,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Metrics ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
            teams.put(admin, it, bob.principal, TeamRole.MEMBER)
        }
    }

    @Test
    fun `counts open changes by state and age, cycle times and the reader's own`() {
        val p = product()
        val pr1 = versioning.create(alice, CreateVersionedObject(TypeId.PROBLEM_REPORT, p, "Noise")).id
        versioning.create(alice, CreateVersionedObject(TypeId.PROBLEM_REPORT, p, "Rattle"))
        val cr = versioning.create(alice, CreateVersionedObject(TypeId.CHANGE_REQUEST, p, "Quieter gears")).id
        versioning.setState(admin, cr, SetState("UNDER_REVIEW"))
        val cn = versioning.create(alice, CreateVersionedObject(TypeId.CHANGE_NOTICE, p, "Helical gears")).id.value
        val ct = versioning.create(alice, CreateVersionedObject(TypeId.CHANGE_TASK, p, "New gear drawing")).id.value
        changes.link(alice, cn, AddChangeLink(ct, ChangeRole.TASK))
        directory.recordSignIn(
            com.plm.identity.api
                .SignIn(TenantId.DEFAULT, bob.principal, "bob", "Bob", null),
        )
        changes.plan(
            alice,
            cn,
            ct,
            SetPlan(
                "bob",
                java.time.LocalDate
                    .now()
                    .plusDays(5),
            ),
        )
        versioning.setState(admin, pr1, SetState("RESOLVED", override = true))

        val m = metrics.metrics(alice, p.value)
        assertThat(m.open).isEqualTo(4)
        assertThat(m.byState.map { Triple(it.kind, it.state, it.count) })
            .contains(
                Triple(ChangeKind.PROBLEM_REPORT, "OPEN", 1),
                Triple(ChangeKind.CHANGE_REQUEST, "UNDER_REVIEW", 1),
            )
        assertThat(m.ages.sumOf { it.upTo7 }).isEqualTo(4)
        assertThat(m.cycleTimes.single { it.kind == ChangeKind.PROBLEM_REPORT }.resolved).isEqualTo(1)
        assertThat(m.flow.last().opened).isEqualTo(5)
        assertThat(m.flow.last().resolved).isEqualTo(1)
        assertThat(m.mine.map { it.why }.toSet()).containsExactly("OWNER")
        val bobs = metrics.metrics(bob, p.value).mine
        assertThat(bobs.single().id).isEqualTo(ct)
        assertThat(bobs.single().why).isEqualTo("ASSIGNEE")
        // Someone outside the product sees none of it.
        assertThat(metrics.metrics(Actor(TenantId.DEFAULT, PrincipalId("carol")), p.value).open).isZero()
    }
}
