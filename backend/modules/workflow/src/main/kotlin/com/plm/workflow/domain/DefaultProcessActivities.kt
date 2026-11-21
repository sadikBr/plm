package com.plm.workflow.domain

import com.plm.access.api.TeamService
import com.plm.identity.api.PrincipalDirectory
import com.plm.notifications.api.PersonalService
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.events.DomainEvents
import com.plm.platform.expression.ExpressionException
import com.plm.platform.expression.ExpressionKind
import com.plm.platform.expression.Expressions
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.ActionKind
import com.plm.workflow.api.NodeKind
import com.plm.workflow.api.ProcessEnded
import com.plm.workflow.api.ProcessNode
import com.plm.workflow.api.ProcessStatus
import com.plm.workflow.api.WorkItemAssigned
import com.plm.workflow.api.WorkItemStatus
import com.plm.workflow.infra.InstanceRow
import com.plm.workflow.infra.ProcessRepository
import com.plm.workflow.infra.WorkItemRow
import io.temporal.failure.ApplicationFailure
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** The principal processes act as: their steps change objects on the process's behalf. */
internal const val PROCESS_ACTOR = "process"

/** What processes do to data, each call in its own transaction (roadmap 5.1). */
@Component
class DefaultProcessActivities internal constructor(
    private val repository: ProcessRepository,
    private val versioning: VersioningService,
    private val teams: TeamService,
    private val directory: PrincipalDirectory,
    private val personal: PersonalService,
    private val events: DomainEvents,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @param:Value("\${plm.ui-url:http://localhost:5173}") private val uiUrl: String,
) : ProcessActivities {
    private fun system(tenant: UUID) = Actor(TenantId(tenant), PrincipalId(PROCESS_ACTOR), siteAdmin = true)

    private fun instance(id: String): InstanceRow =
        repository.instance(UUID.fromString(id)) ?: fail("The process is gone")

    private fun node(
        instance: InstanceRow,
        nodeId: String,
    ): ProcessNode =
        repository.definition(instance.templateId, instance.templateVersion).nodes.first { it.id == nodeId }

    private fun subject(instance: InstanceRow): VersionedObject? =
        instance.subjectId?.let {
            versioning
                .list(
                    system(instance.tenantId),
                    com.plm.versioning.api
                        .ObjectQuery(ids = listOf(MasterId(it)), limit = 1),
                ).firstOrNull()
        }

    private fun fail(message: String): Nothing = throw ApplicationFailure.newNonRetryableFailure(message, "ProcessStep")

    private fun label(node: ProcessNode) = node.label ?: node.id

    /** Who [node]'s assignees are, as principal ids. */
    internal fun resolve(
        instance: InstanceRow,
        node: ProcessNode,
        subject: VersionedObject?,
    ): List<String> {
        val actor = system(instance.tenantId)
        return node.assignees
            .flatMap { a ->
                when {
                    a == "starter" -> listOf(instance.startedBy)
                    a == "owner" -> listOfNotNull(subject?.owner)
                    a.startsWith(
                        "user:",
                    ) -> listOfNotNull(directory.findByUsername(actor, a.removePrefix("user:"))?.id?.value)
                    a.startsWith("role:") && subject != null ->
                        teams
                            .effectiveMembers(actor, subject.contextId)
                            .filter { it.role.key == a.removePrefix("role:") }
                            .map { it.principalId.value }
                    else -> emptyList()
                }
            }.distinct()
    }

    override fun openTasks(
        instanceId: String,
        nodeId: String,
        visit: Int,
    ): Int =
        tx.execute {
            val instance = instance(instanceId)
            repository.itemsOf(instance.id, nodeId, visit).takeIf { it.isNotEmpty() }?.let { return@execute it.size }
            val node = node(instance, nodeId)
            val subject = subject(instance)
            val people = resolve(instance, node, subject)
            if (people.isEmpty()) fail("Nobody to give “${label(node)}” to: ${node.assignees.joinToString(", ")}")
            val now = clock.instant()
            val due = node.dueIn?.let { now.plus(Duration.parse(it)) }
            people.forEach { p ->
                val id = UuidV7.generate(clock)
                repository.insertItem(
                    WorkItemRow(
                        id = id,
                        tenantId = instance.tenantId,
                        instanceId = instance.id,
                        nodeId = node.id,
                        visit = visit,
                        kind = node.kind,
                        title = label(node),
                        instructions = node.instructions,
                        assignee = p,
                        choices = node.choices.ifEmpty { listOf("DONE") },
                        signature = node.signature,
                        status = WorkItemStatus.OPEN,
                        outcome = null,
                        comment = null,
                        subjectId = instance.subjectId,
                        createdAt = now,
                        dueAt = due,
                        completedAt = null,
                        completedBy = null,
                    ),
                )
                events.publish(
                    system(instance.tenantId),
                    WorkItemAssigned(id, instance.id, p, label(node), instance.subjectId),
                )
                val what = subject?.let { " · ${it.number} ${it.name}" }.orEmpty()
                personal.notify(
                    TenantId(instance.tenantId),
                    p,
                    "New task: ${label(node)}$what",
                    node.instructions,
                    "$uiUrl/inbox?task=$id",
                    instance.subjectId,
                    PROCESS_ACTOR,
                )
            }
            repository.setCurrent(instance.id, node.id, label(node))
            val names = people.map { directory.displayName(TenantId(instance.tenantId), PrincipalId(it)) }
            repository.event(
                instance.id,
                now,
                "TASK_OPENED",
                node.id,
                null,
                "${label(node)}: ${names.joinToString(", ")}",
            )
            people.size
        }

    override fun closeTasks(
        instanceId: String,
        nodeId: String,
        visit: Int,
        outcome: String,
    ) {
        tx.executeWithoutResult {
            val instance = instance(instanceId)
            val node = node(instance, nodeId)
            val now = clock.instant()
            repository.cancelOpen(instance.id, nodeId, visit, now)
            repository.setCurrent(instance.id, nodeId, null)
            // A task's answer is its outcome (already in the history); a vote's outcome is worth a line.
            if (outcome != "CANCELLED" && node.kind == NodeKind.VOTE) {
                repository.event(instance.id, now, "DECIDED", nodeId, null, "${label(node)}: $outcome")
            }
        }
    }

    override fun overdue(
        instanceId: String,
        nodeId: String,
        visit: Int,
    ) {
        tx.executeWithoutResult {
            val instance = instance(instanceId)
            val node = node(instance, nodeId)
            val open = repository.itemsOf(instance.id, nodeId, visit).filter { it.status == WorkItemStatus.OPEN }
            open.forEach {
                personal.notify(
                    TenantId(instance.tenantId),
                    it.assignee,
                    "Overdue: ${it.title}",
                    "This task was due ${it.dueAt}.",
                    "$uiUrl/inbox?task=${it.id}",
                    instance.subjectId,
                    PROCESS_ACTOR,
                )
            }
            repository.event(
                instance.id,
                clock.instant(),
                "OVERDUE",
                nodeId,
                null,
                "${label(node)} is overdue: ${open.size} reminded",
            )
        }
    }

    override fun act(
        instanceId: String,
        nodeId: String,
    ): String =
        tx.execute {
            val instance = instance(instanceId)
            val node = node(instance, nodeId)
            val actor = system(instance.tenantId)
            val id = instance.subjectId?.let(::MasterId)
            val message =
                try {
                    when (node.action) {
                        ActionKind.SET_STATE -> {
                            id ?: fail("“${label(node)}” needs an object")
                            val o =
                                versioning.setState(
                                    actor,
                                    id,
                                    SetState(node.state!!, "${instance.name}: ${label(node)}"),
                                )
                            "${o.number} is now ${o.latest.state}"
                        }
                        ActionKind.SET_ATTRIBUTE -> {
                            id ?: fail("“${label(node)}” needs an object")
                            val wc = versioning.checkOut(actor, id)
                            versioning.updateWorkingCopy(actor, id, wc.attributes + (node.attribute!! to node.value))
                            val o = versioning.checkIn(actor, id, "${instance.name}: ${label(node)}")
                            "${node.attribute} set on ${o.number} (${o.latest.version})"
                        }
                        ActionKind.NOTIFY -> {
                            val people = resolve(instance, node, subject(instance))
                            people.forEach {
                                personal.notify(
                                    TenantId(instance.tenantId),
                                    it,
                                    node.message ?: label(node),
                                    null,
                                    instance.subjectId?.let { s -> "$uiUrl/objects/$s" },
                                    instance.subjectId,
                                    PROCESS_ACTOR,
                                )
                            }
                            "Told ${people.size} ${if (people.size == 1) "person" else "people"}"
                        }
                        null -> fail("“${label(node)}” has no action")
                    }
                } catch (e: PlmException) {
                    fail("${label(node)}: ${e.message}")
                }
            repository.event(instance.id, clock.instant(), "ACTION", node.id, PROCESS_ACTOR, "${label(node)}: $message")
            message
        }

    override fun evaluate(
        instanceId: String,
        nodeId: String,
    ): Boolean =
        tx.execute {
            val instance = instance(instanceId)
            val node = node(instance, nodeId)
            val model =
                instance.subjectId
                    ?.let { versioning.expressionModel(system(instance.tenantId), MasterId(it)) }
                    .orEmpty() + ("process" to mapOf("name" to instance.name, "startedBy" to instance.startedBy))
            val result =
                try {
                    Expressions.evaluate(node.expression!!, ExpressionKind.CONDITION, model) == true
                } catch (e: ExpressionException) {
                    fail("${label(node)}: ${e.message}")
                }
            repository.event(
                instance.id,
                clock.instant(),
                "CONDITION",
                node.id,
                null,
                "${label(node)}: ${if (result) "yes" else "no"}",
            )
            result
        }

    override fun note(
        instanceId: String,
        nodeId: String?,
        kind: String,
        message: String,
    ) {
        tx.executeWithoutResult {
            repository.event(
                UUID.fromString(instanceId),
                clock.instant(),
                kind,
                nodeId,
                null,
                message,
            )
        }
    }

    override fun finish(
        instanceId: String,
        status: String,
        outcome: String?,
    ) {
        tx.executeWithoutResult {
            val instance = instance(instanceId)
            val now = clock.instant()
            val s = ProcessStatus.valueOf(status)
            repository.cancelOpen(instance.id, at = now)
            if (repository.finish(instance.id, s, outcome, now)) {
                val message =
                    when (s) {
                        ProcessStatus.COMPLETED -> "Ended: ${outcome ?: "done"}"
                        ProcessStatus.CANCELLED -> "Cancelled"
                        else -> "Failed"
                    }
                repository.event(
                    instance.id,
                    now,
                    if (s ==
                        ProcessStatus.COMPLETED
                    ) {
                        "ENDED"
                    } else {
                        s.name
                    },
                    null,
                    null,
                    message,
                )
                events.publish(
                    system(instance.tenantId),
                    ProcessEnded(instance.id, instance.name, instance.subjectId, s, outcome),
                )
                personal.notify(
                    TenantId(instance.tenantId),
                    instance.startedBy,
                    "${instance.name}: $message",
                    null,
                    instance.subjectId?.let { "$uiUrl/objects/$it?tab=processes" },
                    instance.subjectId,
                    PROCESS_ACTOR,
                )
            }
        }
    }

    /** Whether [node] is a step people answer. */
    internal fun isTask(node: ProcessNode) = node.kind == NodeKind.TASK || node.kind == NodeKind.VOTE
}
