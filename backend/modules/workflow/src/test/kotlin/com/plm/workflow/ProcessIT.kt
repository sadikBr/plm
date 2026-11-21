package com.plm.workflow

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.ActionKind
import com.plm.workflow.api.CompleteWorkItem
import com.plm.workflow.api.NodeKind
import com.plm.workflow.api.ProcessDefinition
import com.plm.workflow.api.ProcessNode
import com.plm.workflow.api.ProcessService
import com.plm.workflow.api.ProcessStatus
import com.plm.workflow.api.ProcessTemplateService
import com.plm.workflow.api.SaveProcessTemplate
import com.plm.workflow.api.StartProcess
import com.plm.workflow.api.VoteRule
import com.plm.workflow.api.WorkItem
import com.plm.workflow.api.WorkItemService
import com.plm.workflow.api.WorkItemStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Processes on Temporal (roadmap 5.1): templates run step by step, people answer tasks. */
@PlmIntegrationTest
internal class ProcessIT(
    @Autowired private val processes: ProcessService,
    @Autowired private val templates: ProcessTemplateService,
    @Autowired private val items: WorkItemService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val mona = Actor(TenantId.DEFAULT, PrincipalId("mona-manager"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))
    private val review = UUID.fromString("00000000-0000-7000-8000-000000000501")

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Proc ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
            teams.put(admin, it, bob.principal, TeamRole.MEMBER)
            teams.put(admin, it, mona.principal, TeamRole.MANAGER)
        }
    }

    private fun <T> eventually(
        what: String,
        get: () -> T?,
    ): T {
        repeat(150) {
            get()?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun task(
        who: Actor,
        instance: UUID,
        title: String,
    ): WorkItem =
        eventually("$title for ${who.principal}") {
            items.list(who).firstOrNull {
                it.instanceId == instance &&
                    it.title == title &&
                    it.status == WorkItemStatus.OPEN
            }
        }

    private fun ended(
        instance: UUID,
        who: Actor = alice,
    ) = eventually(
        "the end of $instance",
    ) { processes.get(who, instance).takeIf { it.status != ProcessStatus.RUNNING } }

    private fun state(id: MasterId) = versioning.get(alice, id).latest.state

    @Test
    fun `a review sends the object back, then releases it, step by step`() {
        val p = product()
        val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Bracket"))
        val run = processes.start(alice, StartProcess(review, part.id.value, "Ready for review"))
        assertThat(run.status).isEqualTo(ProcessStatus.RUNNING)
        assertThatThrownBy { processes.start(alice, StartProcess(review, part.id.value)) }
            .hasMessageContaining("already running")

        val first = task(mona, run.id, "Review")
        assertThat(state(part.id)).isEqualTo("UNDER_REVIEW")
        assertThat(first.subjectNumber).isEqualTo(part.number)
        assertThat(processes.get(alice, run.id).current).containsExactly("Review")
        // Only the assignee answers, and only with one of its choices.
        assertThatThrownBy {
            items.complete(alice, first.id, CompleteWorkItem("APPROVE"))
        }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy {
            items.complete(mona, first.id, CompleteWorkItem("MAYBE"))
        }.isInstanceOf(ValidationException::class.java)
        items.complete(mona, first.id, CompleteWorkItem("REJECT", "The hole pattern is off"))

        val rework = task(alice, run.id, "Rework")
        assertThat(state(part.id)).isEqualTo("IN_WORK")
        items.complete(alice, rework.id, CompleteWorkItem("RESUBMIT"))
        val second = task(mona, run.id, "Review")
        assertThat(second.id).isNotEqualTo(first.id)
        items.complete(mona, second.id, CompleteWorkItem("APPROVE"))

        val done = ended(run.id)
        assertThat(done.status).isEqualTo(ProcessStatus.COMPLETED)
        assertThat(done.outcome).isEqualTo("APPROVED")
        assertThat(state(part.id)).isEqualTo("RELEASED")
        val history = processes.history(alice, run.id).map { it.kind }
        assertThat(history.first()).isEqualTo("STARTED")
        assertThat(history.last()).isEqualTo("ENDED")
        assertThat(history.count { it == "TASK_DONE" }).isEqualTo(3)
        assertThat(processes.forSubject(alice, part.id.value).single().outcome).isEqualTo("APPROVED")
    }

    @Test
    fun `votes decide by their rule, conditions branch, and a cancelled process closes its tasks`() {
        val p = product()
        val vote =
            templates.create(
                admin,
                SaveProcessTemplate(
                    "Board ${UUID.randomUUID().toString().take(6)}",
                    definition =
                        ProcessDefinition(
                            "big",
                            listOf(
                                ProcessNode(
                                    "big",
                                    NodeKind.CONDITION,
                                    expression = "object.name.startsWith('Big')",
                                    outcomes = mapOf("true" to "vote", "false" to "small"),
                                ),
                                ProcessNode(
                                    "vote",
                                    NodeKind.VOTE,
                                    "Board vote",
                                    assignees = listOf("role:MANAGER", "starter"),
                                    choices = listOf("APPROVE", "REJECT"),
                                    rule = VoteRule.MAJORITY,
                                    outcomes = mapOf("APPROVE" to "yes", "REJECT" to "no"),
                                ),
                                ProcessNode(
                                    "small",
                                    NodeKind.ACTION,
                                    "Tell",
                                    action = ActionKind.NOTIFY,
                                    assignees = listOf("starter"),
                                    next = "yes",
                                ),
                                ProcessNode("yes", NodeKind.END, outcome = "APPROVED"),
                                ProcessNode("no", NodeKind.END, outcome = "REJECTED"),
                            ),
                        ),
                ),
            )
        // Two voters, a majority needs both.
        val big = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Big frame"))
        val run = processes.start(alice, StartProcess(vote.id, big.id.value))
        val ballot = task(mona, run.id, "Board vote")
        items.complete(mona, ballot.id, CompleteWorkItem("REJECT"))
        Thread.sleep(300)
        assertThat(processes.get(alice, run.id).status).isEqualTo(ProcessStatus.RUNNING)
        items.complete(alice, task(alice, run.id, "Board vote").id, CompleteWorkItem("REJECT"))
        assertThat(ended(run.id).outcome).isEqualTo("REJECTED")

        val small = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Small pin"))
        assertThat(
            ended(processes.start(alice, StartProcess(vote.id, small.id.value)).id).outcome,
        ).isEqualTo("APPROVED")

        val again = processes.start(alice, StartProcess(review, small.id.value))
        val open = task(mona, again.id, "Review")
        assertThatThrownBy { processes.cancel(bob, again.id, null) }.isInstanceOf(ForbiddenException::class.java)
        processes.cancel(alice, again.id, "Not needed")
        assertThat(ended(again.id).status).isEqualTo(ProcessStatus.CANCELLED)
        assertThat(items.get(mona, open.id).status).isEqualTo(WorkItemStatus.CANCELLED)
    }

    @Test
    fun `a task given to a role is taken on by one member, who can give it back (roadmap 5_3)`() {
        val p = product()
        val pool =
            templates.create(
                admin,
                SaveProcessTemplate(
                    "Pool ${UUID.randomUUID().toString().take(6)}",
                    definition =
                        ProcessDefinition(
                            "check",
                            listOf(
                                ProcessNode(
                                    "check",
                                    NodeKind.TASK,
                                    "Check drawing",
                                    assignees = listOf("role:MEMBER"),
                                    choices = listOf("DONE"),
                                    next = "end",
                                ),
                                ProcessNode("end", NodeKind.END),
                            ),
                        ),
                ),
            )
        val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Drawing"))
        val run = processes.start(alice, StartProcess(pool.id, part.id.value))
        val bobs = task(bob, run.id, "Check drawing")
        val alices = task(alice, run.id, "Check drawing")
        assertThat(bobs.sharedWith).isEqualTo(2)
        assertThat(items.counts(bob).open).isGreaterThanOrEqualTo(1)
        items.claim(bob, bobs.id)
        assertThat(items.get(alice, alices.id).claimedBy).isEqualTo(bob.principal.value)
        assertThatThrownBy {
            items.complete(alice, alices.id, CompleteWorkItem("DONE"))
        }.hasMessageContaining("taken this task on")
        items.release(bob, bobs.id)
        items.complete(alice, alices.id, CompleteWorkItem("DONE"))
        assertThat(ended(run.id).status).isEqualTo(ProcessStatus.COMPLETED)
        assertThat(items.get(bob, bobs.id).status).isEqualTo(WorkItemStatus.CANCELLED)
    }

    @Test
    fun `templates are checked before they're saved, and each save is a new version`() {
        val broken =
            ProcessDefinition(
                "a",
                listOf(
                    ProcessNode("a", NodeKind.TASK, "Approve", choices = listOf("OK")),
                    ProcessNode("lost", NodeKind.END),
                ),
            )
        assertThat(templates.check(broken))
            .anyMatch {
                it.contains("who does it")
            }.anyMatch { it.contains("where does OK lead") }
        assertThatThrownBy { templates.create(admin, SaveProcessTemplate("Broken", definition = broken)) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { templates.create(alice, SaveProcessTemplate("Mine", definition = broken)) }
            .isInstanceOf(ForbiddenException::class.java)
        val ok = ProcessDefinition("end", listOf(ProcessNode("end", NodeKind.END)))
        val t =
            templates.create(
                admin,
                SaveProcessTemplate("Quick ${UUID.randomUUID().toString().take(6)}", definition = ok),
            )
        templates.update(admin, t.id, SaveProcessTemplate(t.name, "Now described", ok))
        assertThat(templates.versions(admin, t.id).map { it.version }).containsExactly(2, 1)
    }
}
