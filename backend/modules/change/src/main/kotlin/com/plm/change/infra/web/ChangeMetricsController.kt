package com.plm.change.infra.web

import com.plm.change.api.ChangeMetricsService
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Change dashboards (roadmap 5.12). */
@RestController
internal class ChangeMetricsController(
    private val metrics: ChangeMetricsService,
) {
    @GetMapping("/api/v1/change-metrics")
    fun metrics(
        actor: Actor,
        @RequestParam(required = false) contextId: UUID?,
        @RequestParam(defaultValue = "180") periodDays: Int,
        @RequestParam(defaultValue = "12") weeks: Int,
    ) = metrics.metrics(actor, contextId, periodDays, weeks)
}
