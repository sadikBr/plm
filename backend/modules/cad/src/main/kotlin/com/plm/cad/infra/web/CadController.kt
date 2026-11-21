package com.plm.cad.infra.web

import com.plm.cad.api.AddDependency
import com.plm.cad.api.CadService
import com.plm.cad.api.UpdateDependency
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

/** CAD documents and their dependencies (roadmap 6.1). */
@RestController
internal class CadController(
    private val cad: CadService,
) {
    @GetMapping("/api/v1/objects/{id}/cad/dependencies")
    fun dependencies(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = cad.dependencies(actor, id, workingCopy)

    @GetMapping("/api/v1/objects/{id}/cad/tree")
    fun tree(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "30") depth: Int,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = cad.tree(actor, id, depth, workingCopy)

    @GetMapping("/api/v1/objects/{id}/cad/where-used")
    fun whereUsed(
        actor: Actor,
        @PathVariable id: UUID,
    ) = cad.whereUsed(actor, id)

    @PostMapping("/api/v1/objects/{id}/cad/dependencies")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: AddDependency,
    ) = cad.add(actor, id, request)

    @PutMapping("/api/v1/objects/{id}/cad/dependencies/{dependencyId}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable dependencyId: UUID,
        @RequestBody request: UpdateDependency,
    ) = cad.update(actor, id, dependencyId, request)

    @DeleteMapping("/api/v1/objects/{id}/cad/dependencies/{dependencyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable dependencyId: UUID,
    ) = cad.remove(actor, id, dependencyId)
}
