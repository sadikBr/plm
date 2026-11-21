package com.plm.automation.infra.web

import com.plm.automation.api.AutomationService
import com.plm.automation.api.AutomationSettings
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** No-code automations (roadmap 5.16). */
@RestController
internal class AutomationController(
    private val automations: AutomationService,
) {
    @GetMapping("/api/v1/automation-triggers")
    fun triggers(actor: Actor) = automations.triggers(actor)

    @GetMapping("/api/v1/automations")
    fun list(actor: Actor) = automations.list(actor)

    @PostMapping("/api/v1/automations")
    @ResponseStatus(HttpStatus.CREATED)
    fun define(
        actor: Actor,
        @RequestBody settings: AutomationSettings,
    ) = automations.define(actor, settings)

    @PutMapping("/api/v1/automations/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody settings: AutomationSettings,
    ) = automations.update(actor, id, settings)

    @DeleteMapping("/api/v1/automations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = automations.delete(actor, id)

    @GetMapping("/api/v1/automations/{id}/runs")
    fun runs(
        actor: Actor,
        @PathVariable id: UUID,
    ) = automations.runs(actor, id)
}
