package com.plm.workflow.domain

import com.plm.workflow.api.NodeKind
import com.plm.workflow.api.ProcessDefinition
import com.plm.workflow.api.ProcessNode
import com.plm.workflow.api.VoteRule
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

/** What a running process asks of the application: everything that touches data (roadmap 5.1). */
@ActivityInterface
interface ProcessActivities {
    /** Gives the step's work items to its assignees; returns how many. Repeating a call opens no more. */
    fun openTasks(
        instanceId: String,
        nodeId: String,
        visit: Int,
    ): Int

    /** Cancels the step's work items still open: its outcome is decided. */
    fun closeTasks(
        instanceId: String,
        nodeId: String,
        visit: Int,
        outcome: String,
    )

    /** Reminds the assignees of open work items that are overdue, and tells who escalates to (roadmap 5.4). */
    fun overdue(
        instanceId: String,
        nodeId: String,
        visit: Int,
    )

    /** Runs an ACTION step; a failure that retrying won't fix fails the step. */
    fun act(
        instanceId: String,
        nodeId: String,
    ): String

    /** A CONDITION step's expression, on the subject. */
    fun evaluate(
        instanceId: String,
        nodeId: String,
    ): Boolean

    /** Writes a line to the process history. */
    fun note(
        instanceId: String,
        nodeId: String?,
        kind: String,
        message: String,
    )

    fun finish(
        instanceId: String,
        status: String,
        outcome: String?,
    )
}

@WorkflowInterface
interface ProcessWorkflow {
    /** Runs the [definitionJson] (the template version it started with) to its end; returns the outcome. */
    @WorkflowMethod
    fun run(
        instanceId: String,
        definitionJson: String,
    ): String

    /** Someone answered a work item of step [nodeId] (pass [visit]). */
    @SignalMethod
    fun answered(
        nodeId: String,
        visit: Int,
        workItemId: String,
        outcome: String,
    )

    @SignalMethod
    fun cancel(reason: String)
}

const val PROCESS_QUEUE = "plm-processes"

fun workflowId(instanceId: java.util.UUID) = "process-$instanceId"

/**
 * One interpreter for every template (roadmap 5.1). It only routes: each step's work is an
 * activity, so replaying the history after a restart is deterministic.
 */
class ProcessWorkflowImpl : ProcessWorkflow {
    private val activities =
        Workflow.newActivityStub(
            ProcessActivities::class.java,
            ActivityOptions
                .newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .setRetryOptions(
                    RetryOptions
                        .newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumAttempts(5)
                        .build(),
                ).build(),
        )
    private val answers = mutableMapOf<String, LinkedHashMap<String, String>>()
    private val visits = mutableMapOf<String, Int>()
    private var cancelled: String? = null
    private var ended: String? = null
    private lateinit var instance: String
    private lateinit var nodes: Map<String, ProcessNode>

    override fun run(
        instanceId: String,
        definitionJson: String,
    ): String {
        instance = instanceId
        val definition = Definitions.read(definitionJson)
        nodes = definition.nodes.associateBy { it.id }
        return try {
            val outcome = path(definition.start, null) ?: ended ?: "DONE"
            if (cancelled != null) {
                activities.finish(instance, "CANCELLED", null)
                "CANCELLED"
            } else {
                activities.finish(instance, "COMPLETED", outcome)
                outcome
            }
        } catch (e: ActivityFailure) {
            val message = generateSequence<Throwable>(e) { it.cause }.last().message ?: "A step failed"
            activities.note(instance, null, "FAILED", message.substringAfter("message='").substringBefore("', type="))
            activities.finish(instance, "FAILED", null)
            "FAILED"
        }
    }

    override fun answered(
        nodeId: String,
        visit: Int,
        workItemId: String,
        outcome: String,
    ) {
        answers.getOrPut("$nodeId#$visit") { LinkedHashMap() }[workItemId] = outcome
    }

    override fun cancel(reason: String) {
        cancelled = reason
    }

    private fun stopped() = cancelled != null || ended != null

    /** Follows steps from [from] until an END (its outcome) or [stopAt] (null), or the process stops. */
    private fun path(
        from: String,
        stopAt: String?,
    ): String? {
        var id: String? = from
        while (id != null && id != stopAt && !stopped()) {
            val node = nodes.getValue(id)
            id =
                when (node.kind) {
                    NodeKind.TASK, NodeKind.VOTE -> decide(node)?.let { node.outcomes[it] ?: node.next }
                    NodeKind.ACTION -> {
                        activities.act(instance, node.id)
                        node.next
                    }
                    NodeKind.CONDITION -> node.outcomes[activities.evaluate(instance, node.id).toString()]
                    NodeKind.PARALLEL -> {
                        val join = node.join!!
                        val branches: List<Promise<String?>> =
                            node.branches.map { b ->
                                Async.function<String?> { path(b, join) }
                            }
                        Workflow.await { stopped() || branches.all { it.isCompleted } }
                        branches.filter { it.isCompleted }.forEach { it.get() }
                        nodes[join]?.next
                    }
                    NodeKind.JOIN -> node.next
                    NodeKind.TIMER -> {
                        activities.note(instance, node.id, "TIMER", "Waiting ${node.duration}")
                        Workflow.await(Duration.parse(node.duration)) { stopped() }
                        node.next
                    }
                    NodeKind.END -> {
                        if (ended == null) ended = node.outcome ?: "DONE"
                        return ended
                    }
                }
        }
        return if (stopped()) ended else null
    }

    /** Opens a TASK or VOTE step's work items and waits until its answers decide (or a reminder is due). */
    private fun decide(node: ProcessNode): String? {
        val visit = (visits[node.id] ?: 0) + 1
        visits[node.id] = visit
        val key = "${node.id}#$visit"
        val count = activities.openTasks(instance, node.id, visit)
        val due = node.dueIn?.let { Duration.parse(it) }
        var verdict: String? = null
        var reminded = false
        while (verdict == null && !stopped()) {
            val decided = { verdict(node, answers[key].orEmpty(), count) }
            if (due != null && !reminded) {
                val inTime = Workflow.await(due) { stopped() || decided() != null }
                if (!inTime) {
                    activities.overdue(instance, node.id, visit)
                    reminded = true
                }
            } else {
                Workflow.await { stopped() || decided() != null }
            }
            verdict = decided()
        }
        activities.closeTasks(instance, node.id, visit, verdict ?: "CANCELLED")
        return verdict
    }

    companion object {
        /**
         * The outcome [answers] (work item → choice) decide for [node], with [count] work items; null
         * while undecided. The first choice is the positive one (APPROVE), the others negative.
         */
        fun verdict(
            node: ProcessNode,
            answers: Map<String, String>,
            count: Int,
        ): String? {
            if (answers.isEmpty()) return null
            if (node.kind == NodeKind.TASK) return answers.values.first()
            val yes = node.choices.firstOrNull() ?: return answers.values.first()
            val all = answers.size >= count
            return when (node.rule ?: VoteRule.ALL) {
                VoteRule.ALL -> answers.values.firstOrNull { it != yes } ?: (if (all) yes else null)
                VoteRule.ANY ->
                    if (yes in answers.values) {
                        yes
                    } else if (all) {
                        answers.values.last()
                    } else {
                        null
                    }
                VoteRule.MAJORITY -> {
                    val tally = answers.values.groupingBy { it }.eachCount()
                    tally.entries.firstOrNull { it.value * 2 > count }?.key
                        ?: if (all) {
                            val top = tally.values.max()
                            tally.filterValues { it == top }.keys.let {
                                if (yes in it &&
                                    it.size == 1
                                ) {
                                    yes
                                } else {
                                    node.choices.last()
                                }
                            }
                        } else {
                            null
                        }
                }
            }
        }
    }
}

/** Reads and writes definitions as JSON: they travel to Temporal as text. */
object Definitions {
    private val json =
        tools.jackson.databind.json.JsonMapper
            .builder()
            .addModule(
                tools.jackson.module.kotlin.KotlinModule
                    .Builder()
                    .build(),
            ).configure(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build()

    fun read(text: String): ProcessDefinition = json.readValue(text, ProcessDefinition::class.java)

    fun write(definition: ProcessDefinition): String = json.writeValueAsString(definition)
}
