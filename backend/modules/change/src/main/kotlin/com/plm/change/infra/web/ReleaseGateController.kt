package com.plm.change.infra.web

import com.plm.change.api.ReleaseGateService
import com.plm.change.api.SetReleaseGate
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** The release gate (roadmap 5.13). */
@RestController
internal class ReleaseGateController(
    private val gate: ReleaseGateService,
) {
    @GetMapping("/api/v1/release-gate")
    fun get(actor: Actor) = gate.get(actor)

    @PutMapping("/api/v1/release-gate")
    fun set(
        actor: Actor,
        @RequestBody request: SetReleaseGate,
    ) = gate.set(actor, request)
}
