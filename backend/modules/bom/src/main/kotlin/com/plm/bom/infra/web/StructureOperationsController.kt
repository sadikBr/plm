package com.plm.bom.infra.web

import com.plm.bom.api.MassReplace
import com.plm.bom.api.StructureOperationsService
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Structure operations on many parts (roadmap 4.14); follow the job at /api/v1/jobs/{id}. */
@RestController
internal class StructureOperationsController(
    private val operations: StructureOperationsService,
) {
    @PostMapping("/api/v1/structure-operations/replace")
    fun replace(
        actor: Actor,
        @RequestBody request: MassReplace,
    ) = operations.replaceEverywhere(actor, request)
}
