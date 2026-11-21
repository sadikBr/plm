package com.plm.bom.infra.web

import com.plm.bom.api.AddAlternate
import com.plm.bom.api.AddSubstitute
import com.plm.bom.api.ReplacementService
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class ReplacementController(
    private val replacements: ReplacementService,
) {
    @GetMapping("/api/v1/objects/{id}/alternates")
    fun alternates(
        actor: Actor,
        @PathVariable id: UUID,
    ) = replacements.alternates(actor, MasterId(id))

    @PostMapping("/api/v1/objects/{id}/alternates")
    @ResponseStatus(HttpStatus.CREATED)
    fun addAlternate(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: AddAlternate,
    ) = replacements.addAlternate(actor, MasterId(id), request)

    @DeleteMapping("/api/v1/objects/{id}/alternates/{alternateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeAlternate(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable alternateId: UUID,
    ) = replacements.removeAlternate(actor, MasterId(id), alternateId)

    @GetMapping("/api/v1/objects/{id}/usages/{usageId}/substitutes")
    fun substitutes(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable usageId: UUID,
    ) = replacements.substitutes(actor, MasterId(id), usageId)

    @PostMapping("/api/v1/objects/{id}/usages/{usageId}/substitutes")
    @ResponseStatus(HttpStatus.CREATED)
    fun addSubstitute(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable usageId: UUID,
        @RequestBody request: AddSubstitute,
    ) = replacements.addSubstitute(actor, MasterId(id), usageId, request)

    @DeleteMapping("/api/v1/objects/{id}/usages/{usageId}/substitutes/{substituteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeSubstitute(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable usageId: UUID,
        @PathVariable substituteId: UUID,
    ) = replacements.removeSubstitute(actor, MasterId(id), usageId, substituteId)

    /** Lines naming the part as a substitute (shown with where-used). */
    @GetMapping("/api/v1/objects/{id}/substitute-uses")
    fun substituteUses(
        actor: Actor,
        @PathVariable id: UUID,
    ) = replacements.substituteUses(actor, MasterId(id))
}
