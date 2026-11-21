package com.plm.change

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.change.api.AddChangeLink
import com.plm.change.api.ChangeFrom
import com.plm.change.api.ChangeKind
import com.plm.change.api.ChangeRole
import com.plm.change.api.ChangeService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ValidationException
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

/** Change objects (roadmap 5.8): numbered by kind, linked in a chain, carrying what they affect. */
@PlmIntegrationTest
internal class ChangeIT(
    @Autowired private val changes: ChangeService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val directory: com.plm.identity.api.PrincipalDirectory,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    @Test
    fun `a problem report leads to a request, a notice and its tasks, which carry what's affected`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Chg ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val bracket = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Bracket"))
        val drawing = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, p, "Bracket drawing"))
        val rover = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Rover"))

        val pr = versioning.create(alice, CreateVersionedObject(TypeId.PROBLEM_REPORT, p, "Bracket cracks under load"))
        assertThat(pr.number).startsWith("PR-")
        assertThat(pr.latest.state).isEqualTo("OPEN")
        assertThat(changes.kindOf(alice, pr.id.value)).isEqualTo(ChangeKind.PROBLEM_REPORT)
        changes.link(alice, pr.id.value, AddChangeLink(bracket.id.value, ChangeRole.AFFECTED, "Seen on 3 prototypes"))
        changes.link(alice, pr.id.value, AddChangeLink(rover.id.value, ChangeRole.END_ITEM))
        assertThatThrownBy { changes.link(alice, pr.id.value, AddChangeLink(drawing.id.value, ChangeRole.END_ITEM)) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { changes.link(alice, pr.id.value, AddChangeLink(bracket.id.value, ChangeRole.RESULTING)) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { changes.link(alice, pr.id.value, AddChangeLink(bracket.id.value, ChangeRole.AFFECTED)) }
            .isInstanceOf(ConflictException::class.java)

        // Full track: the request addresses the report and takes its affected objects and end items.
        val cr = changes.createFrom(alice, ChangeFrom(pr.id.value, "Thicken bracket web", ChangeKind.CHANGE_REQUEST))
        changes.link(alice, cr, AddChangeLink(drawing.id.value, ChangeRole.AFFECTED))
        val crView = changes.view(alice, cr)
        assertThat(versioning.get(alice, MasterId(cr)).number).startsWith("CR-")
        assertThat(crView.links.map { it.role to it.target.number })
            .contains(
                ChangeRole.AFFECTED to bracket.number,
                ChangeRole.END_ITEM to rover.number,
                ChangeRole.ADDRESSES to pr.number,
            )
        assertThat(
            changes
                .view(alice, pr.id.value)
                .linkedFrom
                .single()
                .change!!
                .id,
        ).isEqualTo(cr)

        val cn = changes.createFrom(alice, ChangeFrom(cr, "Bracket web 4 mm", ChangeKind.CHANGE_NOTICE))
        val task =
            changes.createFrom(
                alice,
                ChangeFrom(cn, "Revise the drawing", ChangeKind.CHANGE_TASK, affected = listOf(drawing.id.value)),
            )
        val cnView = changes.view(alice, cn)
        assertThat(cnView.links.filter { it.role == ChangeRole.TASK }.map { it.target.id }).containsExactly(task)
        assertThat(cnView.links.filter { it.role == ChangeRole.IMPLEMENTS }.map { it.target.id }).containsExactly(cr)
        assertThat(changes.view(alice, task).links.map { it.role to it.target.id }).containsExactly(
            ChangeRole.AFFECTED to drawing.id.value,
        )
        changes.link(alice, cn, AddChangeLink(bracket.id.value, ChangeRole.RESULTING))

        // Seen from the part: every change it's in.
        assertThat(
            changes.changesOf(alice, bracket.id.value).map {
                it.role to
                    versioning.get(alice, MasterId(it.changeId)).number.take(3)
            },
        ).contains(
            ChangeRole.AFFECTED to "PR-",
            ChangeRole.AFFECTED to "CR-",
            ChangeRole.AFFECTED to "CN-",
            ChangeRole.RESULTING to "CN-",
        )

        // Under review, a change's links are fixed.
        versioning.setState(admin, MasterId(cr), SetState("UNDER_REVIEW"))
        assertThatThrownBy { changes.link(alice, cr, AddChangeLink(rover.id.value, ChangeRole.AFFECTED)) }
            .isInstanceOf(ConflictException::class.java)
        versioning.setState(admin, MasterId(cr), SetState("APPROVED"))

        // The plan (roadmap 5.9): who does the task, by when.
        directory.recordSignIn(
            com.plm.identity.api
                .SignIn(TenantId.DEFAULT, alice.principal, "alice", "Alice", null),
        )
        val entry =
            changes.plan(
                alice,
                cn,
                task,
                com.plm.change.api
                    .SetPlan("alice", java.time.LocalDate.of(2020, 1, 1)),
            )
        assertThat(entry.assignee).isEqualTo(alice.principal.value)
        assertThat(entry.overdue).isTrue()

        // The notice can't be resolved while its task is open; once it is, everything follows.
        versioning.setState(admin, MasterId(cn), SetState("IMPLEMENTATION"))
        assertThatThrownBy {
            versioning.setState(admin, MasterId(cn), SetState("RESOLVED"))
        }.hasMessageContaining("aren't done")
        versioning.setState(admin, MasterId(task), SetState("IMPLEMENTATION"))
        versioning.setState(admin, MasterId(task), SetState("RESOLVED"))
        versioning.setState(admin, MasterId(cn), SetState("RESOLVED"))
        assertThat(versioning.get(alice, bracket.id).latest.state).isEqualTo("RELEASED")
        assertThat(versioning.get(alice, MasterId(cr)).latest.state).isEqualTo("RESOLVED")
        assertThat(versioning.get(alice, pr.id).latest.state).isEqualTo("RESOLVED")
    }
}
