package com.plm.integration.infra.web

import com.plm.integration.api.Acknowledge
import com.plm.integration.api.DistributionService
import com.plm.integration.api.SaveDistributionRule
import com.plm.integration.api.SaveTarget
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** ERP distribution (roadmap 9.5). */
@RestController
internal class DistributionController(
    private val distribution: DistributionService,
) {
    @GetMapping("/api/v1/distribution/targets")
    fun targets(actor: Actor) = distribution.targets(actor)

    @PostMapping("/api/v1/distribution/targets")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody command: SaveTarget,
    ) = distribution.createTarget(actor, command)

    @PutMapping("/api/v1/distribution/targets/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: SaveTarget,
    ) = distribution.updateTarget(actor, id, command)

    @DeleteMapping("/api/v1/distribution/targets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = distribution.deleteTarget(actor, id)

    @PostMapping("/api/v1/distribution/targets/{id}/rules")
    fun addRule(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: SaveDistributionRule,
    ) = distribution.addRule(actor, id, command)

    @DeleteMapping("/api/v1/distribution/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeRule(
        actor: Actor,
        @PathVariable id: UUID,
    ) = distribution.removeRule(actor, id)

    @GetMapping("/api/v1/objects/{id}/distribution")
    fun status(
        actor: Actor,
        @PathVariable id: UUID,
    ) = distribution.status(actor, id)

    @PutMapping("/api/v1/objects/{id}/distribution/{targetId}/plant-values")
    fun plantValues(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable targetId: UUID,
        @RequestBody values: Map<String, String>,
    ) = distribution.setPlantValues(actor, id, targetId, values)

    @PostMapping("/api/v1/objects/{id}/distribution/{targetId}/send")
    fun send(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable targetId: UUID,
    ) = distribution.send(actor, id, targetId)

    @GetMapping("/api/v1/distribution/messages")
    fun messages(
        actor: Actor,
        @RequestParam(required = false) objectId: UUID?,
        @RequestParam(required = false) targetId: UUID?,
    ) = distribution.messages(actor, objectId, targetId)
}

/** What the ERP calls: no user, the target's secret is the key. */
@RestController
internal class DistributionErpController(
    private val distribution: DistributionService,
) {
    @PostMapping("/api/v1/distribution/targets/{id}/fetch")
    fun fetch(
        @PathVariable id: UUID,
        @RequestHeader("X-PLM-Target-Secret") secret: String,
        @RequestParam(defaultValue = "100") limit: Int,
    ) = distribution.fetch(id, secret, limit)

    @PostMapping("/api/v1/distribution/messages/{id}/ack")
    fun ack(
        @PathVariable id: UUID,
        @RequestHeader("X-PLM-Target-Secret") secret: String,
        @RequestBody answer: Acknowledge,
    ) = distribution.acknowledge(id, secret, answer)
}
