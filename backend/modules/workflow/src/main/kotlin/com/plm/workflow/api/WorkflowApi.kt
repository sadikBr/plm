package com.plm.workflow.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

/** What a step of a process does (roadmap 5.1, 5.2). */
enum class NodeKind {
    /** People decide: one of [ProcessNode.choices]; the first answer decides. */
    TASK,

    /** People vote: each assignee answers; [ProcessNode.rule] decides the outcome. */
    VOTE,

    /** Done by the system: [ProcessNode.action]. */
    ACTION,

    /** Goes on along `outcomes["true"]` or `outcomes["false"]` by its CEL [ProcessNode.expression]. */
    CONDITION,

    /** Runs [ProcessNode.branches] at the same time, then goes on from [ProcessNode.join]. */
    PARALLEL,

    /** Where parallel branches meet. */
    JOIN,

    /** Waits for [ProcessNode.duration]. */
    TIMER,

    /** Ends the process with [ProcessNode.outcome]. */
    END,
}

/** How the votes of a [NodeKind.VOTE] step decide (roadmap 5.4). */
enum class VoteRule {
    /** Approved when everyone approves; rejected by the first rejection. */
    ALL,

    /** Approved by the first approval; rejected when everyone rejects. */
    ANY,

    /** Whichever choice more than half chose; otherwise the last choice. */
    MAJORITY,
}

/** What an [NodeKind.ACTION] step does. */
enum class ActionKind {
    /** Moves the subject to [ProcessNode.state] along its lifecycle. */
    SET_STATE,

    /** Sets [ProcessNode.attribute] to [ProcessNode.value] on the subject (checked out and in). */
    SET_ATTRIBUTE,

    /** Sends [ProcessNode.message] to [ProcessNode.assignees]. */
    NOTIFY,
}

/**
 * A step of a process template. Fields that don't apply to its [kind] are ignored. Assignees are
 * `user:<username>`, `role:<team role>` (in the subject's context), `starter` or `owner`.
 */
data class ProcessNode(
    val id: String,
    val kind: NodeKind,
    val label: String? = null,
    /** Where to go next, for steps with a single way out. */
    val next: String? = null,
    /** TASK, VOTE: choice → next step; CONDITION: "true"/"false" → next step. */
    val outcomes: Map<String, String> = emptyMap(),
    val assignees: List<String> = emptyList(),
    val instructions: String? = null,
    /** TASK, VOTE: what people can answer, e.g. APPROVE, REJECT. */
    val choices: List<String> = emptyList(),
    val rule: VoteRule? = null,
    /** TASK, VOTE: ISO-8601 duration until it's due, e.g. P3D. */
    val dueIn: String? = null,
    /** TASK: when it's overdue, also given to these (same forms as assignees) (roadmap 5.4). */
    val escalateTo: List<String> = emptyList(),
    /** TASK, VOTE: answering needs an electronic signature (roadmap 5.6). */
    val signature: Boolean = false,
    val action: ActionKind? = null,
    val state: String? = null,
    val attribute: String? = null,
    val value: Any? = null,
    val message: String? = null,
    val expression: String? = null,
    val branches: List<String> = emptyList(),
    val join: String? = null,
    val duration: String? = null,
    val outcome: String? = null,
    /** Where the editor draws it. */
    val x: Int? = null,
    val y: Int? = null,
)

data class ProcessDefinition(
    val start: String,
    val nodes: List<ProcessNode>,
)

data class ProcessTemplate(
    val id: UUID,
    val name: String,
    val description: String?,
    /** The version new processes run. */
    val version: Int,
    val definition: ProcessDefinition,
    val active: Boolean,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class SaveProcessTemplate(
    val name: String,
    val description: String? = null,
    val definition: ProcessDefinition,
    val active: Boolean = true,
)

data class ProcessTemplateVersion(
    val version: Int,
    val createdAt: Instant,
    val createdBy: String,
)

enum class ProcessStatus { RUNNING, COMPLETED, CANCELLED, FAILED }

data class ProcessInstance(
    val id: UUID,
    val templateId: UUID,
    val templateVersion: Int,
    val name: String,
    /** The object it's about. */
    val subjectId: UUID?,
    val subjectNumber: String?,
    val status: ProcessStatus,
    /** How it ended, e.g. APPROVED. */
    val outcome: String?,
    val startedBy: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val comment: String?,
    /** Steps open right now (their labels). */
    val current: List<String>,
)

data class ProcessEvent(
    val at: Instant,
    /** STARTED, STEP, TASK_OPENED, TASK_DONE, ACTION, CONDITION, ENDED, CANCELLED, FAILED... */
    val kind: String,
    val nodeId: String?,
    val actor: String?,
    val message: String,
)

data class StartProcess(
    val templateId: UUID,
    val subjectId: UUID? = null,
    val comment: String? = null,
)

enum class WorkItemStatus { OPEN, DONE, CANCELLED }

/** A task for a person (roadmap 5.3). */
data class WorkItem(
    val id: UUID,
    val instanceId: UUID,
    val processName: String,
    val nodeId: String,
    val kind: NodeKind,
    val title: String,
    val instructions: String?,
    val assignee: String,
    val choices: List<String>,
    val signature: Boolean,
    val status: WorkItemStatus,
    val outcome: String?,
    val comment: String?,
    val subjectId: UUID?,
    val subjectNumber: String?,
    val subjectName: String?,
    val createdAt: Instant,
    val dueAt: Instant?,
    val completedAt: Instant?,
    val completedBy: String?,
    /** Who took the step on: only they can answer until they release it (roadmap 5.3). */
    val claimedBy: String? = null,
    /** How many people the step was given to. */
    val sharedWith: Int = 1,
    /** Received for someone else: as their stand-in, by reassignment or escalation (roadmap 5.4). */
    val onBehalfOf: String? = null,
    /** VOTE: how many chose what so far. */
    val tally: Map<String, Int> = emptyMap(),
    val rule: VoteRule? = null,
)

data class ReassignTask(
    /** The username of who gets it. */
    val to: String,
    val comment: String? = null,
)

/** Who gets someone's new tasks while they're away (roadmap 5.4). */
data class StandIn(
    val standIn: String,
    val from: java.time.LocalDate?,
    val until: java.time.LocalDate?,
)

data class SetStandIn(
    /** The stand-in's username. */
    val username: String,
    val from: java.time.LocalDate? = null,
    val until: java.time.LocalDate? = null,
)

interface StandInService {
    fun get(actor: Actor): StandIn?

    fun set(
        actor: Actor,
        request: SetStandIn,
    ): StandIn

    fun clear(actor: Actor)
}

data class TaskCounts(
    val open: Int,
    val overdue: Int,
)

data class CompleteWorkItem(
    val outcome: String,
    val comment: String? = null,
)

data class WorkItemQuery(
    /** Everyone's (administrators) instead of the actor's own. */
    val all: Boolean = false,
    val status: WorkItemStatus? = WorkItemStatus.OPEN,
    val limit: Int = 200,
)

/** Process templates (roadmap 5.1, 5.2). Changing one needs a site administrator. */
interface ProcessTemplateService {
    fun list(actor: Actor): List<ProcessTemplate>

    fun get(
        actor: Actor,
        id: UUID,
    ): ProcessTemplate

    /** Problems with a definition, empty when it can run. */
    fun check(definition: ProcessDefinition): List<String>

    fun create(
        actor: Actor,
        request: SaveProcessTemplate,
    ): ProcessTemplate

    /** Saves a new version: running processes keep the one they started with. */
    fun update(
        actor: Actor,
        id: UUID,
        request: SaveProcessTemplate,
    ): ProcessTemplate

    fun versions(
        actor: Actor,
        id: UUID,
    ): List<ProcessTemplateVersion>
}

/**
 * Processes (roadmap 5.1): durable, on Temporal, so they survive restarts and deploys. Each runs
 * the template version it started with. Starting one on an object needs read access to it.
 */
interface ProcessService {
    fun start(
        actor: Actor,
        request: StartProcess,
    ): ProcessInstance

    fun get(
        actor: Actor,
        id: UUID,
    ): ProcessInstance

    /** Processes about [subjectId], newest first. */
    fun forSubject(
        actor: Actor,
        subjectId: UUID,
    ): List<ProcessInstance>

    fun list(
        actor: Actor,
        status: ProcessStatus? = null,
        limit: Int = 100,
    ): List<ProcessInstance>

    fun history(
        actor: Actor,
        id: UUID,
    ): List<ProcessEvent>

    /** Its starter or a site administrator. */
    fun cancel(
        actor: Actor,
        id: UUID,
        reason: String?,
    ): ProcessInstance
}

/** Tasks for people (roadmap 5.3). */
interface WorkItemService {
    fun list(
        actor: Actor,
        query: WorkItemQuery = WorkItemQuery(),
    ): List<WorkItem>

    fun forProcess(
        actor: Actor,
        instanceId: UUID,
    ): List<WorkItem>

    fun get(
        actor: Actor,
        id: UUID,
    ): WorkItem

    fun counts(actor: Actor): TaskCounts

    /** Takes on a step given to several people: theirs wait until it's released or answered. */
    fun claim(
        actor: Actor,
        id: UUID,
    ): WorkItem

    fun release(
        actor: Actor,
        id: UUID,
    ): WorkItem

    /** Gives the task to someone else: its assignee, whoever started the process, or an administrator. */
    fun reassign(
        actor: Actor,
        id: UUID,
        request: ReassignTask,
    ): WorkItem

    /** Its assignee only; the outcome must be one of its choices. */
    fun complete(
        actor: Actor,
        id: UUID,
        request: CompleteWorkItem,
    ): WorkItem
}

data class ProcessStarted(
    val instanceId: UUID,
    val templateId: UUID,
    val name: String,
    val subjectId: UUID?,
) : DomainEvent {
    override val eventType = "workflow.ProcessStarted.v1"
    override val aggregateType get() = "process"
    override val aggregateId get() = instanceId
}

data class ProcessEnded(
    val instanceId: UUID,
    val name: String,
    val subjectId: UUID?,
    val status: ProcessStatus,
    val outcome: String?,
) : DomainEvent {
    override val eventType = "workflow.ProcessEnded.v1"
    override val aggregateType get() = "process"
    override val aggregateId get() = instanceId
}

data class WorkItemAssigned(
    val workItemId: UUID,
    val instanceId: UUID,
    val assignee: String,
    val title: String,
    val subjectId: UUID?,
) : DomainEvent {
    override val eventType = "workflow.WorkItemAssigned.v1"
    override val aggregateType get() = "work-item"
    override val aggregateId get() = workItemId
}

data class WorkItemCompleted(
    val workItemId: UUID,
    val instanceId: UUID,
    val outcome: String,
    val comment: String?,
    val subjectId: UUID?,
) : DomainEvent {
    override val eventType = "workflow.WorkItemCompleted.v1"
    override val aggregateType get() = "work-item"
    override val aggregateId get() = workItemId
}

data class ProcessTemplateChanged(
    val templateId: UUID,
    val name: String,
    val version: Int,
) : DomainEvent {
    override val eventType = "workflow.ProcessTemplateChanged.v1"
    override val aggregateType get() = "process-template"
    override val aggregateId get() = templateId
}
