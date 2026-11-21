package com.plm.bom.infra.web

import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.CompareSide
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ConfigSpecType
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
import java.time.Instant
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

    /**
     * The multi-level structure (roadmap 4.2, 4.3): `spec` LATEST, STATE (with `state`, Released
     * by default) or AS_OF (with `asOf`); `depth` levels (1-50).
     */
    @GetMapping("/api/v1/objects/{id}/structure")
    fun structure(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "LATEST") spec: ConfigSpecType,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) asOf: Instant?,
        @RequestParam(defaultValue = "1") depth: Int,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = bom.structure(actor, MasterId(id), ConfigSpec(spec, state, asOf), depth, workingCopy)

    /** Where the part is used (roadmap 4.6), under a configuration specification, `depth` levels up. */
    @GetMapping("/api/v1/objects/{id}/where-used")
    fun whereUsed(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "LATEST") spec: ConfigSpecType,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) asOf: Instant?,
        @RequestParam(defaultValue = "1") depth: Int,
    ) = bom.whereUsed(actor, MasterId(id), ConfigSpec(spec, state, asOf), depth)

    /** Compares two structures (roadmap 4.9): each side a part with `…Iteration` or `…Spec`. */
    @GetMapping("/api/v1/structure-compare")
    fun compare(
        actor: Actor,
        @RequestParam left: UUID,
        @RequestParam(required = false) leftIteration: UUID?,
        @RequestParam(defaultValue = "LATEST") leftSpec: ConfigSpecType,
        @RequestParam(required = false) leftState: String?,
        @RequestParam right: UUID,
        @RequestParam(required = false) rightIteration: UUID?,
        @RequestParam(defaultValue = "LATEST") rightSpec: ConfigSpecType,
        @RequestParam(required = false) rightState: String?,
        @RequestParam(defaultValue = "50") depth: Int,
    ) = bom.compare(
        actor,
        CompareSide(left, leftIteration, ConfigSpec(leftSpec, leftState)),
        CompareSide(right, rightIteration, ConfigSpec(rightSpec, rightState)),
        depth,
    )

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

    @PutMapping("/api/v1/objects/{id}/usages/{usageId}/child")
    fun replace(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable usageId: UUID,
        @RequestBody request: ReplaceChild,
    ) = bom.replace(actor, MasterId(id), usageId, request.childId)

    @PutMapping("/api/v1/objects/{id}/usages/order")
    fun reorder(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: UsageIds,
    ) = bom.reorder(actor, MasterId(id), request.usageIds)

    @PostMapping("/api/v1/objects/{id}/usages/paste")
    @ResponseStatus(HttpStatus.CREATED)
    fun paste(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: UsageIds,
    ) = bom.paste(actor, MasterId(id), request.usageIds)
}

data class ReplaceChild(
    val childId: UUID,
)

data class UsageIds(
    val usageIds: List<UUID>,
)
