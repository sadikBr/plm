package com.plm.jobs.infra.web

import com.plm.jobs.api.JobQuery
import com.plm.jobs.api.JobScheduleService
import com.plm.jobs.api.JobService
import com.plm.jobs.api.JobStatus
import com.plm.jobs.api.ScheduleSettings
import com.plm.jobs.api.SubmitJob
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class JobController(
    private val jobs: JobService,
    private val schedules: JobScheduleService,
) {
    @GetMapping("/api/v1/job-types")
    fun types(actor: Actor) = jobs.types(actor)

    /** Jobs, newest first: everyone's for administrators (unless `mine`), otherwise your own. */
    @GetMapping("/api/v1/jobs")
    fun list(
        actor: Actor,
        @RequestParam(required = false) status: JobStatus?,
        @RequestParam(required = false) type: String?,
        @RequestParam(defaultValue = "false") mine: Boolean,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = jobs.list(actor, JobQuery(status, type, mine, limit))

    @GetMapping("/api/v1/jobs/stats")
    fun stats(actor: Actor) = jobs.stats(actor)

    @PostMapping("/api/v1/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(
        actor: Actor,
        @RequestBody request: SubmitJob,
    ) = jobs.submit(actor, request)

    @GetMapping("/api/v1/jobs/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = jobs.get(actor, id)

    @GetMapping("/api/v1/jobs/{id}/log")
    fun log(
        actor: Actor,
        @PathVariable id: UUID,
    ) = jobs.log(actor, id)

    @PostMapping("/api/v1/jobs/{id}/cancel")
    fun cancel(
        actor: Actor,
        @PathVariable id: UUID,
    ) = jobs.cancel(actor, id)

    @PostMapping("/api/v1/jobs/{id}/retry")
    fun retry(
        actor: Actor,
        @PathVariable id: UUID,
    ) = jobs.retry(actor, id)

    @GetMapping("/api/v1/job-schedules")
    fun schedules(actor: Actor) = schedules.list(actor)

    @PostMapping("/api/v1/job-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSchedule(
        actor: Actor,
        @RequestBody settings: ScheduleSettings,
    ) = schedules.create(actor, settings)

    @PutMapping("/api/v1/job-schedules/{id}")
    fun updateSchedule(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody settings: ScheduleSettings,
    ) = schedules.update(actor, id, settings)

    @DeleteMapping("/api/v1/job-schedules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteSchedule(
        actor: Actor,
        @PathVariable id: UUID,
    ) = schedules.delete(actor, id)

    @PostMapping("/api/v1/job-schedules/{id}/run")
    fun runNow(
        actor: Actor,
        @PathVariable id: UUID,
    ) = schedules.runNow(actor, id)
}
