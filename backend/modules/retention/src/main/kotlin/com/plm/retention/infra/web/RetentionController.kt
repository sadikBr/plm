package com.plm.retention.infra.web

import com.plm.platform.actor.Actor
import com.plm.retention.api.RetentionService
import com.plm.retention.api.SaveLegalHold
import com.plm.retention.api.SaveRetentionRule
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

/** Retention, legal holds and archives (roadmap 9.8). */
@RestController
internal class RetentionController(
    private val retention: RetentionService,
) {
    @GetMapping("/api/v1/retention/rules")
    fun rules(actor: Actor) = retention.rules(actor)

    @PostMapping("/api/v1/retention/rules")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody command: SaveRetentionRule,
    ) = retention.saveRule(actor, null, command)

    @PutMapping("/api/v1/retention/rules/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: SaveRetentionRule,
    ) = retention.saveRule(actor, id, command)

    @DeleteMapping("/api/v1/retention/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = retention.deleteRule(actor, id)

    @GetMapping("/api/v1/retention/preview")
    fun preview(actor: Actor) = retention.preview(actor)

    @PostMapping("/api/v1/retention/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun run(actor: Actor) = retention.run(actor)

    @GetMapping("/api/v1/legal-holds")
    fun holds(actor: Actor) = retention.holds(actor)

    @PostMapping("/api/v1/legal-holds")
    @ResponseStatus(HttpStatus.CREATED)
    fun createHold(
        actor: Actor,
        @RequestBody command: SaveLegalHold,
    ) = retention.createHold(actor, command)

    @PostMapping("/api/v1/legal-holds/{id}/release")
    fun release(
        actor: Actor,
        @PathVariable id: UUID,
    ) = retention.releaseHold(actor, id)

    @GetMapping("/api/v1/objects/{id}/legal-holds")
    fun holdsOn(
        actor: Actor,
        @PathVariable id: UUID,
    ) = retention.holdsOn(actor, id)

    @GetMapping("/api/v1/archives")
    fun archives(actor: Actor) = retention.archives(actor)

    @PostMapping("/api/v1/objects/{id}/archive")
    fun archive(
        actor: Actor,
        @PathVariable id: UUID,
    ) = retention.archive(actor, id)

    @PostMapping("/api/v1/archives/{id}/restore")
    fun restore(
        actor: Actor,
        @PathVariable id: UUID,
    ) = mapOf("objectId" to retention.restore(actor, id))
}
