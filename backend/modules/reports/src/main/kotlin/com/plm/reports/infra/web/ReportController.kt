package com.plm.reports.infra.web

import com.plm.platform.actor.Actor
import com.plm.reports.api.DashboardService
import com.plm.reports.api.DashboardSettings
import com.plm.reports.api.ReportService
import com.plm.reports.api.ReportSettings
import com.plm.search.api.SearchQuery
import com.plm.search.api.SearchService
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
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
internal class ReportController(
    private val reports: ReportService,
    private val dashboards: DashboardService,
    private val versioning: VersioningService,
    private val search: SearchService,
) {
    @GetMapping("/api/v1/reports")
    fun list(actor: Actor) = reports.list(actor)

    @GetMapping("/api/v1/reports/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = reports.get(actor, id)

    @PostMapping("/api/v1/reports")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody settings: ReportSettings,
    ) = reports.create(actor, settings)

    @PutMapping("/api/v1/reports/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody settings: ReportSettings,
    ) = reports.update(actor, id, settings)

    @DeleteMapping("/api/v1/reports/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = reports.delete(actor, id)

    @PostMapping("/api/v1/reports/{id}/run")
    fun run(
        actor: Actor,
        @PathVariable id: UUID,
    ) = reports.run(actor, id)

    @PostMapping("/api/v1/reports/preview")
    fun preview(
        actor: Actor,
        @RequestBody settings: ReportSettings,
    ) = reports.preview(actor, settings)

    @GetMapping("/api/v1/reports/{id}/runs")
    fun runs(
        actor: Actor,
        @PathVariable id: UUID,
    ) = reports.runs(actor, id)

    @GetMapping("/api/v1/dashboards")
    fun dashboards(actor: Actor) = dashboards.list(actor)

    @GetMapping("/api/v1/dashboards/{id}")
    fun dashboard(
        actor: Actor,
        @PathVariable id: UUID,
    ) = dashboards.get(actor, id)

    @PostMapping("/api/v1/dashboards")
    @ResponseStatus(HttpStatus.CREATED)
    fun createDashboard(
        actor: Actor,
        @RequestBody settings: DashboardSettings,
    ) = dashboards.create(actor, settings)

    @PutMapping("/api/v1/dashboards/{id}")
    fun updateDashboard(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody settings: DashboardSettings,
    ) = dashboards.update(actor, id, settings)

    @DeleteMapping("/api/v1/dashboards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDashboard(
        actor: Actor,
        @PathVariable id: UUID,
    ) = dashboards.delete(actor, id)

    /** Your home page: your chosen dashboard, or the default one. */
    @GetMapping("/api/v1/home")
    fun home(actor: Actor) = dashboards.home(actor)

    @PutMapping("/api/v1/home")
    fun setHome(
        actor: Actor,
        @RequestBody request: HomeRequest,
    ) = dashboards.setHome(actor, request.dashboardId)

    /** Objects you have checked out. */
    @GetMapping("/api/v1/home/checked-out")
    fun checkedOut(
        actor: Actor,
        @RequestParam(defaultValue = "10") limit: Int,
    ) = versioning.list(actor, ObjectQuery(checkedOutBy = actor.principal.value, limit = limit.coerceIn(1, 100))).map {
        mapOf(
            "id" to it.id.value,
            "number" to it.number,
            "name" to it.name,
            "typeId" to it.typeId.value,
            "version" to it.latest.version,
            "state" to it.latest.state,
        )
    }

    /** What changed lately, where you can read. */
    @GetMapping("/api/v1/home/recent")
    fun recent(
        actor: Actor,
        @RequestParam(defaultValue = "10") limit: Int,
    ) = search.search(actor, SearchQuery(sort = "MODIFIED", limit = limit.coerceIn(1, 50))).hits
}

internal data class HomeRequest(
    val dashboardId: UUID? = null,
)
