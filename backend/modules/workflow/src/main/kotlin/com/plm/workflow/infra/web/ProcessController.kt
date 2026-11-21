package com.plm.workflow.infra.web

import com.plm.platform.actor.Actor
import com.plm.workflow.api.CompleteWorkItem
import com.plm.workflow.api.ProcessDefinition
import com.plm.workflow.api.ProcessService
import com.plm.workflow.api.ProcessStatus
import com.plm.workflow.api.ProcessTemplateService
import com.plm.workflow.api.SaveProcessTemplate
import com.plm.workflow.api.StartProcess
import com.plm.workflow.api.WorkItemQuery
import com.plm.workflow.api.WorkItemService
import com.plm.workflow.api.WorkItemStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class CheckResult(
    val errors: List<String>,
)

internal data class CancelProcess(
    val reason: String? = null,
)

/** Process templates (roadmap 5.1, 5.2). */
@RestController
internal class ProcessTemplateController(
    private val templates: ProcessTemplateService,
) {
    @GetMapping("/api/v1/process-templates")
    fun list(actor: Actor) = templates.list(actor)

    @GetMapping("/api/v1/process-templates/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = templates.get(actor, id)

    @GetMapping("/api/v1/process-templates/{id}/versions")
    fun versions(
        actor: Actor,
        @PathVariable id: UUID,
    ) = templates.versions(actor, id)

    /** What's wrong with a definition (empty: it can run). Saves nothing. */
    @PostMapping("/api/v1/process-templates/check")
    fun check(
        @RequestBody definition: ProcessDefinition,
    ) = CheckResult(templates.check(definition))

    @PostMapping("/api/v1/process-templates")
    fun create(
        actor: Actor,
        @RequestBody request: SaveProcessTemplate,
    ) = templates.create(actor, request)

    /** A new version; running processes keep theirs. */
    @PutMapping("/api/v1/process-templates/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveProcessTemplate,
    ) = templates.update(actor, id, request)
}

/** Processes (roadmap 5.1). */
@RestController
internal class ProcessController(
    private val processes: ProcessService,
    private val items: WorkItemService,
) {
    @PostMapping("/api/v1/processes")
    fun start(
        actor: Actor,
        @RequestBody request: StartProcess,
    ) = processes.start(actor, request)

    @GetMapping("/api/v1/processes")
    fun list(
        actor: Actor,
        @RequestParam(required = false) status: ProcessStatus?,
        @RequestParam(defaultValue = "100") limit: Int,
    ) = processes.list(actor, status, limit)

    @GetMapping("/api/v1/processes/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = processes.get(actor, id)

    @GetMapping("/api/v1/processes/{id}/history")
    fun history(
        actor: Actor,
        @PathVariable id: UUID,
    ) = processes.history(actor, id)

    @GetMapping("/api/v1/processes/{id}/tasks")
    fun tasks(
        actor: Actor,
        @PathVariable id: UUID,
    ) = items.forProcess(actor, id)

    @PostMapping("/api/v1/processes/{id}/cancel")
    fun cancel(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: CancelProcess,
    ) = processes.cancel(actor, id, request.reason)

    @GetMapping("/api/v1/objects/{id}/processes")
    fun forObject(
        actor: Actor,
        @PathVariable id: UUID,
    ) = processes.forSubject(actor, id)
}

/** Tasks for people (roadmap 5.3). */
@RestController
internal class WorkItemController(
    private val items: WorkItemService,
) {
    @GetMapping("/api/v1/tasks")
    fun list(
        actor: Actor,
        @RequestParam(defaultValue = "false") all: Boolean,
        @RequestParam(required = false) status: WorkItemStatus?,
        @RequestParam(defaultValue = "false") anyStatus: Boolean,
        @RequestParam(defaultValue = "200") limit: Int,
    ) = items.list(actor, WorkItemQuery(all, if (anyStatus) null else status ?: WorkItemStatus.OPEN, limit))

    /** Open and overdue tasks of the caller: the top bar's counter. */
    @GetMapping("/api/v1/tasks/counts")
    fun counts(actor: Actor) = items.counts(actor)

    @PostMapping("/api/v1/tasks/{id}/claim")
    fun claim(
        actor: Actor,
        @PathVariable id: UUID,
    ) = items.claim(actor, id)

    @PostMapping("/api/v1/tasks/{id}/release")
    fun release(
        actor: Actor,
        @PathVariable id: UUID,
    ) = items.release(actor, id)

    @PostMapping("/api/v1/tasks/{id}/reassign")
    fun reassign(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: com.plm.workflow.api.ReassignTask,
    ) = items.reassign(actor, id, request)

    @GetMapping("/api/v1/tasks/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = items.get(actor, id)

    @PostMapping("/api/v1/tasks/{id}/complete")
    fun complete(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: CompleteWorkItem,
    ) = items.complete(actor, id, request)
}

/** Who gets my tasks while I'm away (roadmap 5.4). */
@RestController
internal class StandInController(
    private val standIns: com.plm.workflow.api.StandInService,
) {
    @GetMapping("/api/v1/me/stand-in")
    fun get(actor: Actor) = standIns.get(actor)

    @org.springframework.web.bind.annotation.PutMapping("/api/v1/me/stand-in")
    fun set(
        actor: Actor,
        @RequestBody request: com.plm.workflow.api.SetStandIn,
    ) = standIns.set(actor, request)

    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/me/stand-in")
    fun clear(actor: Actor) = standIns.clear(actor)
}

/** Which process runs for which type, and when (roadmap 5.5). */
@RestController
internal class ProcessAssignmentController(
    private val assignments: com.plm.workflow.api.ProcessAssignmentService,
) {
    @GetMapping("/api/v1/process-assignments")
    fun list(
        actor: Actor,
        @RequestParam(required = false) contextId: UUID?,
    ) = assignments.list(actor, contextId)

    @PostMapping("/api/v1/process-assignments")
    fun create(
        actor: Actor,
        @RequestBody request: com.plm.workflow.api.SaveProcessAssignment,
    ) = assignments.create(actor, request)

    @PutMapping("/api/v1/process-assignments/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: com.plm.workflow.api.SaveProcessAssignment,
    ) = assignments.update(actor, id, request)

    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/process-assignments/{id}")
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = assignments.delete(actor, id)

    /** The assignments in effect for an object: what starts by itself, what's offered. */
    @GetMapping("/api/v1/objects/{id}/process-assignments")
    fun forObject(
        actor: Actor,
        @PathVariable id: UUID,
    ) = assignments.forObject(actor, id)
}
