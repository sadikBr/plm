package com.plm.bom.infra.web

import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.UpdateUsage
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
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
internal class BomController(
    private val bom: BomService,
) {
    /** The structure of the latest iteration, `?iterationId=`, or the caller's `?workingCopy=true`. */
    @GetMapping("/api/v1/objects/{id}/usages")
    fun usages(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(required = false) iterationId: UUID?,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = bom.usages(actor, MasterId(id), iterationId, workingCopy)

    @PostMapping("/api/v1/objects/{id}/usages")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody usage: AddUsage,
    ) = bom.add(actor, MasterId(id), usage)

    @PutMapping("/api/v1/objects/{id}/usages/{usageId}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable usageId: UUID,
        @RequestBody usage: UpdateUsage,
    ) = bom.update(actor, MasterId(id), usageId, usage)

    @DeleteMapping("/api/v1/objects/{id}/usages/{usageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable usageId: UUID,
    ) = bom.remove(actor, MasterId(id), usageId)
}
