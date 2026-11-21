package com.plm.bom.infra.web

import com.plm.bom.api.BaselineService
import com.plm.bom.api.CollectRequest
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ConfigSpecType
import com.plm.bom.api.NewBaseline
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class BaselineMembers(
    val iterationIds: List<UUID> = emptyList(),
    val masterIds: List<UUID> = emptyList(),
)

@RestController
internal class BaselineController(
    private val baselines: BaselineService,
) {
    @GetMapping("/api/v1/baselines")
    fun list(
        actor: Actor,
        @RequestParam(required = false) containing: UUID?,
    ) = baselines.list(actor, containing?.let(::MasterId))

    @GetMapping("/api/v1/baselines/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = baselines.get(actor, id)

    @GetMapping("/api/v1/baselines/{id}/members")
    fun members(
        actor: Actor,
        @PathVariable id: UUID,
    ) = baselines.members(actor, id)

    @PostMapping("/api/v1/baselines")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: NewBaseline,
    ) = baselines.create(actor, request)

    @PostMapping("/api/v1/baselines/{id}/members")
    fun add(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: BaselineMembers,
    ) = baselines.add(actor, id, request.iterationIds)

    @PostMapping("/api/v1/baselines/{id}/members/remove")
    fun remove(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: BaselineMembers,
    ) = baselines.remove(actor, id, request.masterIds)

    @PostMapping("/api/v1/baselines/{id}/freeze")
    fun freeze(
        actor: Actor,
        @PathVariable id: UUID,
    ) = baselines.freeze(actor, id)

    @DeleteMapping("/api/v1/baselines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = baselines.delete(actor, id)

    /** A part with its structure and documents (roadmap 4.10). */
    @GetMapping("/api/v1/objects/{id}/collect")
    fun collect(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "LATEST") spec: ConfigSpecType,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) baselineId: UUID?,
        @RequestParam(required = false) effectivityKind: String?,
        @RequestParam(required = false) effectiveAt: String?,
        @RequestParam(required = false) endItemId: UUID?,
        @RequestParam(defaultValue = "50") depth: Int,
        @RequestParam(defaultValue = "true") documents: Boolean,
    ) = baselines.collect(
        actor,
        CollectRequest(
            id,
            ConfigSpec(
                spec,
                state,
                baselineId = baselineId,
                effectivityKind = effectivityKind,
                effectiveAt = effectiveAt,
                endItemId = endItemId,
            ),
            depth,
            documents,
        ),
    )
}
