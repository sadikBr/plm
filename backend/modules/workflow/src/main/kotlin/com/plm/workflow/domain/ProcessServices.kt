package com.plm.workflow.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.expression.ExpressionKind
import com.plm.platform.expression.Expressions
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.ActionKind
import com.plm.workflow.api.CompleteWorkItem
import com.plm.workflow.api.NodeKind
import com.plm.workflow.api.ProcessDefinition
import com.plm.workflow.api.ProcessEvent
import com.plm.workflow.api.ProcessInstance
import com.plm.workflow.api.ProcessService
import com.plm.workflow.api.ProcessStarted
import com.plm.workflow.api.ProcessStatus
import com.plm.workflow.api.ProcessTemplate
import com.plm.workflow.api.ProcessTemplateChanged
import com.plm.workflow.api.ProcessTemplateService
import com.plm.workflow.api.ProcessTemplateVersion
import com.plm.workflow.api.SaveProcessTemplate
import com.plm.workflow.api.StartProcess
import com.plm.workflow.api.WorkItem
import com.plm.workflow.api.WorkItemCompleted
import com.plm.workflow.api.WorkItemQuery
import com.plm.workflow.api.WorkItemService
import com.plm.workflow.api.WorkItemStatus
import com.plm.workflow.infra.InstanceRow
import com.plm.workflow.infra.ProcessRepository
import com.plm.workflow.infra.WorkItemRow
import io.temporal.client.WorkflowOptions
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeParseException
import java.util.UUID

/** Runs [block] once the transaction commits (now, without one): Temporal must never see rolled-back work. */
internal fun afterCommit(block: () -> Unit) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = block()
            },
        )
    } else {
        block()
    }
}

@Service
@Transactional
internal class DefaultProcessTemplateService(
    private val repository: ProcessRepository,
    private val events: DomainEvents,
    private val clock: Clock,
) : ProcessTemplateService {
    @Transactional(readOnly = true)
    override fun list(actor: Actor) = repository.templates(actor.tenant)

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: UUID,
    ) = repository.template(actor.tenant, id)
        ?: throw NotFoundException("PROCESS_TEMPLATE_NOT_FOUND", "There's no such process template")

    @Transactional(readOnly = true)
    override fun versions(
        actor: Actor,
        id: UUID,
    ): List<ProcessTemplateVersion> = repository.versions(get(actor, id).id)

    override fun check(definition: ProcessDefinition): List<String> {
        val errors = mutableListOf<String>()
        val ids = definition.nodes.map { it.id }
        ids
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { errors += "Two steps are called $it" }
        if (definition.start !in ids) errors += "The start step ${definition.start} doesn't exist"

        fun ref(
            from: String,
            to: String?,
            what: String,
        ) {
            if (to ==
                null
            ) {
                errors += "$from: $what is missing"
            } else if (to !in
                ids
            ) {
                errors += "$from: $what $to doesn't exist"
            }
        }
        for (n in definition.nodes) {
            val name = n.label ?: n.id
            if (!n.id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) {
                errors +=
                    "${n.id}: a step id is letters, digits, - and _"
            }
            when (n.kind) {
                NodeKind.TASK, NodeKind.VOTE -> {
                    if (n.assignees.isEmpty()) errors += "$name: who does it?"
                    n.assignees
                        .filterNot {
                            it in setOf("starter", "owner") ||
                                it.startsWith("user:") ||
                                it.startsWith("role:")
                        }.forEach {
                            errors += "$name: $it isn't an assignee (user:name, role:KEY, starter or owner)"
                        }
                    if (n.choices.isEmpty()) errors += "$name: what can people answer?"
                    n.choices.forEach { c ->
                        if (n.outcomes[c] == null &&
                            n.next == null
                        ) {
                            errors += "$name: where does $c lead?"
                        }
                    }
                    n.outcomes.forEach { (c, to) ->
                        if (c !in n.choices) errors += "$name: $c isn't one of its choices"
                        ref(name, to, "the step after $c")
                    }
                    n.dueIn?.let { duration(name, it, errors) }
                    if (n.kind == NodeKind.VOTE && n.rule == null) errors += "$name: how do the votes decide?"
                }
                NodeKind.ACTION -> {
                    when (n.action) {
                        ActionKind.SET_STATE -> if (n.state.isNullOrBlank()) errors += "$name: which state?"
                        ActionKind.SET_ATTRIBUTE -> if (n.attribute.isNullOrBlank()) errors += "$name: which attribute?"
                        ActionKind.NOTIFY -> if (n.assignees.isEmpty()) errors += "$name: who is told?"
                        null -> errors += "$name: what does it do?"
                    }
                    ref(name, n.next, "the next step")
                }
                NodeKind.CONDITION -> {
                    if (n.expression.isNullOrBlank()) {
                        errors += "$name: the condition is empty"
                    } else {
                        Expressions.check(n.expression, ExpressionKind.CONDITION).forEach {
                            errors +=
                                "$name: ${it.message}"
                        }
                    }
                    ref(name, n.outcomes["true"], "the step when true")
                    ref(name, n.outcomes["false"], "the step when false")
                }
                NodeKind.PARALLEL -> {
                    if (n.branches.size < 2) errors += "$name: give at least two branches"
                    n.branches.forEach { ref(name, it, "branch") }
                    ref(name, n.join, "the join")
                    if (n.join != null && definition.nodes.firstOrNull { it.id == n.join }?.kind != NodeKind.JOIN) {
                        errors += "$name: its join must be a JOIN step"
                    }
                }
                NodeKind.JOIN -> ref(name, n.next, "the next step")
                NodeKind.TIMER -> {
                    if (n.duration == null) errors += "$name: how long?" else duration(name, n.duration, errors)
                    ref(name, n.next, "the next step")
                }
                NodeKind.END -> Unit
            }
        }
        if (errors.isEmpty()) {
            // Every step reachable from the start, and an end reachable.
            val byId = definition.nodes.associateBy { it.id }
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque(listOf(definition.start))
            while (queue.isNotEmpty()) {
                val n = byId.getValue(queue.removeFirst())
                if (!seen.add(n.id)) continue
                queue += listOfNotNull(n.next, n.join) + n.outcomes.values + n.branches
            }
            (ids - seen).forEach { errors += "${byId.getValue(it).label ?: it} can't be reached" }
            if (definition.nodes.none { it.kind == NodeKind.END && it.id in seen }) {
                errors +=
                    "The process never ends: add an end"
            }
        }
        return errors
    }

    private fun duration(
        name: String,
        text: String,
        errors: MutableList<String>,
    ) {
        try {
            if (Duration.parse(text).isNegative) errors += "$name: $text is negative"
        } catch (_: DateTimeParseException) {
            errors += "$name: $text isn't a duration like PT4H or P3D"
        }
    }

    override fun create(
        actor: Actor,
        request: SaveProcessTemplate,
    ) = save(actor, UuidV7.generate(clock), request, null)

    override fun update(
        actor: Actor,
        id: UUID,
        request: SaveProcessTemplate,
    ) = save(actor, id, request, get(actor, id))

    private fun save(
        actor: Actor,
        id: UUID,
        request: SaveProcessTemplate,
        before: ProcessTemplate?,
    ): ProcessTemplate {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators change process templates",
            )
        }
        val name = request.name.trim()
        if (name.isEmpty() ||
            name.length > 100
        ) {
            throw ValidationException("INVALID_PROCESS_TEMPLATE", "Give the template a name")
        }
        if (repository.templates(actor.tenant).any { it.name.equals(name, ignoreCase = true) && it.id != id }) {
            throw ConflictException("DUPLICATE_PROCESS_TEMPLATE", "There's already a template named $name")
        }
        val errors = check(request.definition)
        if (errors.isNotEmpty()) {
            throw ValidationException("INVALID_PROCESS_TEMPLATE", errors.joinToString("; "), mapOf("errors" to errors))
        }
        val version =
            repository.saveTemplate(
                actor.tenant,
                id,
                name,
                request.description?.trim()?.ifEmpty { null },
                request.active,
                request.definition,
                actor.principal.value,
                clock.instant(),
            )
        events.publish(actor, ProcessTemplateChanged(id, name, version))
        return repository.template(actor.tenant, id)!!.also { if (before == null) Unit }
    }
}

@Service
@Transactional
internal class DefaultProcessService(
    private val repository: ProcessRepository,
    private val templates: ProcessTemplateService,
    private val versioning: VersioningService,
    private val temporal: TemporalRuntime,
    private val events: DomainEvents,
    private val clock: Clock,
) : ProcessService {
    override fun start(
        actor: Actor,
        request: StartProcess,
    ): ProcessInstance {
        val template = templates.get(actor, request.templateId)
        if (!template.active) throw ValidationException("PROCESS_TEMPLATE_INACTIVE", "${template.name} isn't in use")
        // The subject must be readable (404 otherwise).
        request.subjectId?.let { versioning.get(actor, MasterId(it)) }
        val running = request.subjectId?.let { repository.instances(actor.tenant, it, ProcessStatus.RUNNING) }.orEmpty()
        if (running.any { it.templateId == template.id }) {
            throw ConflictException("PROCESS_ALREADY_RUNNING", "${template.name} is already running on it")
        }
        val row =
            InstanceRow(
                id = UuidV7.generate(clock),
                tenantId = actor.tenant.value,
                templateId = template.id,
                templateVersion = template.version,
                name = template.name,
                subjectId = request.subjectId,
                status = ProcessStatus.RUNNING,
                outcome = null,
                startedBy = actor.principal.value,
                startedAt = clock.instant(),
                endedAt = null,
                comment = request.comment?.trim()?.ifEmpty { null },
                current = emptyMap(),
            )
        repository.insertInstance(row)
        repository.event(
            row.id,
            row.startedAt,
            "STARTED",
            null,
            actor.principal.value,
            "Started ${template.name} (version ${template.version})",
        )
        events.publish(actor, ProcessStarted(row.id, template.id, template.name, request.subjectId))
        val definition = Definitions.write(template.definition)
        afterCommit {
            val stub =
                temporal.client.newWorkflowStub(
                    ProcessWorkflow::class.java,
                    WorkflowOptions
                        .newBuilder()
                        .setWorkflowId(workflowId(row.id))
                        .setTaskQueue(PROCESS_QUEUE)
                        .build(),
                )
            io.temporal.client.WorkflowClient
                .start(stub::run, row.id.toString(), definition)
        }
        return view(actor, row)
    }

    private fun row(
        actor: Actor,
        id: UUID,
    ): InstanceRow {
        val row = repository.instance(id)?.takeIf { it.tenantId == actor.tenant.value } ?: notFound()
        if (row.subjectId != null &&
            versioning.list(actor, ObjectQuery(ids = listOf(MasterId(row.subjectId)), limit = 1)).isEmpty()
        ) {
            notFound()
        }
        return row
    }

    private fun notFound(): Nothing = throw NotFoundException("PROCESS_NOT_FOUND", "There's no such process")

    private fun view(
        actor: Actor,
        row: InstanceRow,
        subjects: Map<UUID, String> = emptyMap(),
    ) = ProcessInstance(
        id = row.id,
        templateId = row.templateId,
        templateVersion = row.templateVersion,
        name = row.name,
        subjectId = row.subjectId,
        subjectNumber =
            row.subjectId?.let { s ->
                subjects[s]
                    ?: versioning.list(actor, ObjectQuery(ids = listOf(MasterId(s)), limit = 1)).firstOrNull()?.number
            },
        status = row.status,
        outcome = row.outcome,
        startedBy = row.startedBy,
        startedAt = row.startedAt,
        endedAt = row.endedAt,
        comment = row.comment,
        current = row.current.values.toList(),
    )

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: UUID,
    ) = view(actor, row(actor, id))

    @Transactional(readOnly = true)
    override fun forSubject(
        actor: Actor,
        subjectId: UUID,
    ): List<ProcessInstance> {
        val subject = versioning.get(actor, MasterId(subjectId))
        return repository.instances(actor.tenant, subjectId).map { view(actor, it, mapOf(subjectId to subject.number)) }
    }

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        status: ProcessStatus?,
        limit: Int,
    ): List<ProcessInstance> {
        val rows = repository.instances(actor.tenant, null, status, limit.coerceIn(1, 500) * 2)
        val readable =
            rows
                .mapNotNull { it.subjectId }
                .distinct()
                .chunked(500)
                .flatMap { ids -> versioning.list(actor, ObjectQuery(ids = ids.map(::MasterId), limit = 500)) }
                .associate { it.id.value to it.number }
        return rows
            .filter { it.subjectId == null || it.subjectId in readable }
            .take(limit.coerceIn(1, 500))
            .map { view(actor, it, readable) }
    }

    @Transactional(readOnly = true)
    override fun history(
        actor: Actor,
        id: UUID,
    ): List<ProcessEvent> = repository.events(row(actor, id).id)

    override fun cancel(
        actor: Actor,
        id: UUID,
        reason: String?,
    ): ProcessInstance {
        val row = row(actor, id)
        if (row.startedBy != actor.principal.value && !actor.siteAdmin) {
            throw ForbiddenException("ACCESS_DENIED", "Only whoever started it, or an administrator, can cancel it")
        }
        if (row.status != ProcessStatus.RUNNING) throw ConflictException("PROCESS_ENDED", "It has already ended")
        repository.event(
            row.id,
            clock.instant(),
            "CANCEL",
            null,
            actor.principal.value,
            "Cancelled${reason?.let { ": $it" }.orEmpty()}",
        )
        afterCommit {
            temporal.client
                .newWorkflowStub(ProcessWorkflow::class.java, workflowId(row.id))
                .cancel(reason ?: "")
        }
        return view(actor, row)
    }
}

@Service
@Transactional
internal class DefaultWorkItemService(
    private val repository: ProcessRepository,
    private val versioning: VersioningService,
    private val temporal: TemporalRuntime,
    private val events: DomainEvents,
    private val clock: Clock,
) : WorkItemService {
    private fun view(
        row: WorkItemRow,
        names: Map<UUID, String>,
        subjects: Map<UUID, Pair<String, String>>,
    ) = WorkItem(
        id = row.id,
        instanceId = row.instanceId,
        processName = names[row.instanceId] ?: "",
        nodeId = row.nodeId,
        kind = row.kind,
        title = row.title,
        instructions = row.instructions,
        assignee = row.assignee,
        choices = row.choices,
        signature = row.signature,
        status = row.status,
        outcome = row.outcome,
        comment = row.comment,
        subjectId = row.subjectId,
        subjectNumber = row.subjectId?.let { subjects[it]?.first },
        subjectName = row.subjectId?.let { subjects[it]?.second },
        createdAt = row.createdAt,
        dueAt = row.dueAt,
        completedAt = row.completedAt,
        completedBy = row.completedBy,
    )

    private fun views(
        actor: Actor,
        rows: List<WorkItemRow>,
    ): List<WorkItem> {
        val names = rows.map { it.instanceId }.distinct().associateWith { repository.instance(it)?.name ?: "" }
        val subjects =
            rows
                .mapNotNull { it.subjectId }
                .distinct()
                .chunked(500)
                .flatMap { ids -> versioning.list(actor, ObjectQuery(ids = ids.map(::MasterId), limit = 500)) }
                .associate { it.id.value to (it.number to it.name) }
        return rows.map { view(it, names, subjects) }
    }

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        query: WorkItemQuery,
    ): List<WorkItem> {
        if (query.all &&
            !actor.siteAdmin
        ) {
            throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators see everyone's tasks")
        }
        val rows =
            repository.items(
                actor.tenant,
                if (query.all) null else actor.principal.value,
                query.status,
                query.limit.coerceIn(1, 500),
            )
        return views(actor, rows)
    }

    @Transactional(readOnly = true)
    override fun forProcess(
        actor: Actor,
        instanceId: UUID,
    ): List<WorkItem> {
        val instance = repository.instance(instanceId)?.takeIf { it.tenantId == actor.tenant.value } ?: notFound()
        instance.subjectId?.let { versioning.get(actor, MasterId(it)) }
        return views(actor, repository.itemsOf(instance.id))
    }

    private fun notFound(): Nothing = throw NotFoundException("WORK_ITEM_NOT_FOUND", "There's no such task")

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: UUID,
    ): WorkItem {
        val row = repository.item(id)?.takeIf { it.tenantId == actor.tenant.value } ?: notFound()
        if (row.assignee != actor.principal.value && !actor.siteAdmin) {
            row.subjectId?.let { versioning.get(actor, MasterId(it)) } ?: notFound()
        }
        return views(actor, listOf(row)).single()
    }

    override fun complete(
        actor: Actor,
        id: UUID,
        request: CompleteWorkItem,
    ): WorkItem {
        val row = repository.item(id)?.takeIf { it.tenantId == actor.tenant.value } ?: notFound()
        if (row.assignee !=
            actor.principal.value
        ) {
            throw ForbiddenException("NOT_YOUR_TASK", "This task is someone else's")
        }
        if (row.status != WorkItemStatus.OPEN) throw ConflictException("TASK_CLOSED", "This task is already closed")
        if (request.outcome !in row.choices) {
            throw ValidationException("INVALID_OUTCOME", "Answer one of ${row.choices.joinToString(", ")}")
        }
        val comment = request.comment?.trim()?.ifEmpty { null }
        if ((comment?.length ?: 0) >
            4000
        ) {
            throw ValidationException("INVALID_COMMENT", "A comment is at most 4000 characters")
        }
        val now = clock.instant()
        if (!repository.completeItem(row.id, request.outcome, comment, actor.principal.value, now)) {
            throw ConflictException("TASK_CLOSED", "This task is already closed")
        }
        repository.event(
            row.instanceId,
            now,
            "TASK_DONE",
            row.nodeId,
            actor.principal.value,
            "${row.title}: ${request.outcome}${comment?.let { " — $it" }.orEmpty()}",
        )
        events.publish(actor, WorkItemCompleted(row.id, row.instanceId, request.outcome, comment, row.subjectId))
        afterCommit {
            temporal.client
                .newWorkflowStub(ProcessWorkflow::class.java, workflowId(row.instanceId))
                .answered(row.nodeId, row.visit, row.id.toString(), request.outcome)
        }
        return get(actor, id)
    }
}
