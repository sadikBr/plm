package com.plm.bom.infra.web

import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ConfigSpecType
import com.plm.bom.api.RollupService
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class RollupController(
    private val rollups: RollupService,
) {
    @GetMapping("/api/v1/objects/{id}/rollup-attributes")
    fun attributes(
        actor: Actor,
        @PathVariable id: UUID,
    ) = rollups.attributes(actor, MasterId(id))

    @GetMapping("/api/v1/objects/{id}/rollup")
    fun rollup(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam attribute: String,
        @RequestParam(defaultValue = "LATEST") spec: ConfigSpecType,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) baselineId: UUID?,
    ) = rollups.rollup(actor, MasterId(id), attribute, ConfigSpec(spec, state, baselineId = baselineId))

    @GetMapping("/api/v1/objects/{id}/flattened")
    fun flatten(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "LATEST") spec: ConfigSpecType,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) baselineId: UUID?,
    ) = rollups.flatten(actor, MasterId(id), ConfigSpec(spec, state, baselineId = baselineId))
}
