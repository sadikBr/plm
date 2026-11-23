package com.plm.access.infra.web

import com.plm.access.api.GrantClearance
import com.plm.access.api.SaveAgreement
import com.plm.access.api.SaveSecurityLabel
import com.plm.access.api.SecurityLabelService
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

/** Security labels, clearances and agreements (roadmap 9.7). */
@RestController
internal class SecurityLabelController(
    private val labels: SecurityLabelService,
) {
    @GetMapping("/api/v1/security-labels")
    fun list(actor: Actor) = labels.labels(actor)

    @PostMapping("/api/v1/security-labels")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody command: SaveSecurityLabel,
    ) = labels.createLabel(actor, command)

    @PutMapping("/api/v1/security-labels/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: SaveSecurityLabel,
    ) = labels.updateLabel(actor, id, command)

    @GetMapping("/api/v1/clearances")
    fun clearances(
        actor: Actor,
        @RequestParam(required = false) principalId: String?,
    ) = labels.clearances(actor, principalId)

    @PostMapping("/api/v1/clearances")
    fun grant(
        actor: Actor,
        @RequestBody command: GrantClearance,
    ) = labels.grant(actor, command)

    @DeleteMapping("/api/v1/clearances/{principalId}/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        actor: Actor,
        @PathVariable principalId: String,
        @PathVariable labelId: UUID,
    ) = labels.revoke(actor, principalId, labelId)

    @GetMapping("/api/v1/agreements")
    fun agreements(actor: Actor) = labels.agreements(actor)

    @PostMapping("/api/v1/agreements")
    @ResponseStatus(HttpStatus.CREATED)
    fun createAgreement(
        actor: Actor,
        @RequestBody command: SaveAgreement,
    ) = labels.createAgreement(actor, command)

    @PutMapping("/api/v1/agreements/{id}")
    fun updateAgreement(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: SaveAgreement,
    ) = labels.updateAgreement(actor, id, command)

    @DeleteMapping("/api/v1/agreements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAgreement(
        actor: Actor,
        @PathVariable id: UUID,
    ) = labels.deleteAgreement(actor, id)
}
