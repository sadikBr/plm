package com.plm.workflow

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.NodeKind
import com.plm.workflow.api.ProcessAssignmentService
import com.plm.workflow.api.ProcessDefinition
import com.plm.workflow.api.ProcessNode
import com.plm.workflow.api.ProcessService
import com.plm.workflow.api.ProcessTemplateService
import com.plm.workflow.api.ProcessTrigger
import com.plm.workflow.api.SaveProcessAssignment
import com.plm.workflow.api.SaveProcessTemplate
import com.plm.workflow.api.StartProcess
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Process assignments (roadmap 5.5): what starts by itself, where, for which types. */
@PlmIntegrationTest
internal class ProcessAssignmentIT(
    @Autowired private val assignments: ProcessAssignmentService,
    @Autowired private val processes: ProcessService,
    @Autowired private val templates: ProcessTemplateService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val review = UUID.fromString("00000000-0000-7000-8000-000000000501")

    private fun template(name: String) =
        templates.create(
            admin,
            SaveProcessTemplate(
                "$name ${UUID.randomUUID().toString().take(6)}",
                definition =
                    ProcessDefinition(
                        "wait",
                        listOf(
                            ProcessNode(
                                "wait",
                                NodeKind.TASK,
                                "Look",
                                assignees = listOf("starter"),
                                choices = listOf("DONE"),
                                next = "end",
                            ),
                            ProcessNode("end", NodeKind.END),
                        ),
                    ),
            ),
        )

    private fun names(id: UUID): List<String> {
        repeat(30) {
            val found = processes.forSubject(alice, id).map { it.name }
            if (found.isNotEmpty()) return found
            Thread.sleep(100)
        }
        return emptyList()
    }

    @Test
    fun `assigned processes start by themselves, the nearest context winning, and not from a process's own steps`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Assign ${UUID.randomUUID()}"),
            )
        val rover = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        val drone = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Drone")).id
        listOf(rover, drone).forEach { teams.put(admin, it, alice.principal, TeamRole.MEMBER) }
        val orgWide = template("Org intake")
        val roverOnly = template("Rover intake")
        assignments.create(
            admin,
            SaveProcessAssignment(org.id.value, TypeId.PART.value, ProcessTrigger.ON_CREATE, templateId = orgWide.id),
        )
        assignments.create(
            admin,
            SaveProcessAssignment(rover.value, TypeId.PART.value, ProcessTrigger.ON_CREATE, templateId = roverOnly.id),
        )
        // A member can't assign processes; managing the context can.
        assertThatThrownBy {
            assignments.create(
                alice,
                SaveProcessAssignment(rover.value, TypeId.PART.value, ProcessTrigger.MANUAL, templateId = review),
            )
        }.isInstanceOf(ForbiddenException::class.java)

        val inDrone = versioning.create(alice, CreateVersionedObject(TypeId.PART, drone, "Prop"))
        val inRover = versioning.create(alice, CreateVersionedObject(TypeId.PART, rover, "Wheel"))
        assertThat(names(inDrone.id.value)).containsExactly(orgWide.name)
        assertThat(names(inRover.id.value)).containsExactly(roverOnly.name)
        assertThat(assignments.forObject(alice, inDrone.id.value).single().inherited).isTrue()
        // Documents aren't parts: nothing starts.
        val doc = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, rover, "Spec"))
        Thread.sleep(500)
        assertThat(processes.forSubject(alice, doc.id.value)).isEmpty()

        // Entering a state starts what's assigned to it, unless a process moved the object there.
        val onReview = template("On review")
        assignments.create(
            admin,
            SaveProcessAssignment(rover.value, TypeId.PART.value, ProcessTrigger.ON_STATE, "UNDER_REVIEW", onReview.id),
        )
        val byHand = versioning.create(alice, CreateVersionedObject(TypeId.PART, rover, "Axle"))
        versioning.setState(admin, byHand.id, SetState("UNDER_REVIEW"))
        assertThat(
            names(byHand.id.value).let {
                Thread.sleep(500)
                processes.forSubject(alice, byHand.id.value).map { it.name }
            },
        ).contains(onReview.name)
        val byProcess = versioning.create(alice, CreateVersionedObject(TypeId.PART, rover, "Hub"))
        processes.start(admin, StartProcess(review, byProcess.id.value))
        Thread.sleep(1500)
        assertThat(versioning.get(alice, byProcess.id).latest.state).isEqualTo("UNDER_REVIEW")
        assertThat(processes.forSubject(alice, byProcess.id.value).map { it.name }).doesNotContain(onReview.name)
    }
}
