package com.plm.change.infra.web

import com.plm.change.api.AddRedline
import com.plm.change.api.MassChange
import com.plm.change.api.RedlineService
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Redlines and mass changes (roadmap 5.11). */
@RestController
internal class RedlineController(
    private val redlines: RedlineService,
) {
    @GetMapping("/api/v1/changes/{id}/redlines")
    fun list(
        actor: Actor,
        @PathVariable id: UUID,
    ) = redlines.list(actor, id)

    @PostMapping("/api/v1/changes/{id}/redlines")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: AddRedline,
    ) = redlines.add(actor, id, request)

    @DeleteMapping("/api/v1/changes/{id}/redlines/{redlineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable redlineId: UUID,
    ) = redlines.remove(actor, id, redlineId)

    /** The same edit on each of the change's affected objects it applies to. */
    @PostMapping("/api/v1/changes/{id}/mass-change")
    fun mass(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: MassChange,
    ) = redlines.mass(actor, id, request)
}
