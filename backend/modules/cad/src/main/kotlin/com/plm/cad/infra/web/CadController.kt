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

/** The collector (roadmap 6.2). */
@RestController
internal class CollectorController(
    private val collector: com.plm.cad.api.CollectorService,
) {
    @PostMapping("/api/v1/objects/{id}/cad/collect")
    fun collect(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody options: com.plm.cad.api.CollectOptions,
    ) = collector.collect(actor, id, options)

    @PostMapping("/api/v1/cad/collector")
    fun apply(
        actor: Actor,
        @RequestBody request: com.plm.cad.api.CollectorRequest,
    ) = collector.apply(actor, request)
}

/** Family tables (roadmap 6.3). */
@RestController
internal class FamilyController(
    private val families: com.plm.cad.api.FamilyService,
) {
    @GetMapping("/api/v1/objects/{id}/cad/family")
    fun membership(
        actor: Actor,
        @PathVariable id: UUID,
    ) = families.membership(actor, id)

    @GetMapping("/api/v1/objects/{id}/cad/family/table")
    fun table(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = families.table(actor, id, workingCopy)

    @PutMapping("/api/v1/objects/{id}/cad/family/columns")
    fun columns(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody columns: List<com.plm.cad.api.FamilyColumn>,
    ) = families.setColumns(actor, id, columns)

    @PostMapping("/api/v1/objects/{id}/cad/family/instances")
    fun addInstance(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody instance: com.plm.cad.api.NewInstance,
    ) = families.addInstance(actor, id, instance)

    @PutMapping("/api/v1/objects/{id}/cad/family/instances/{instanceId}")
    fun setValues(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable instanceId: UUID,
        @RequestBody values: Map<String, Any?>,
    ) = families.setValues(actor, id, instanceId, values)

    @DeleteMapping("/api/v1/objects/{id}/cad/family/instances/{instanceId}")
    fun removeInstance(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable instanceId: UUID,
    ) = families.removeInstance(actor, id, instanceId)
}

/** Part ↔ CAD association and build (roadmap 6.4). */
@RestController
internal class AssociationController(
    private val associations: com.plm.cad.api.AssociationService,
) {
    @GetMapping("/api/v1/objects/{id}/cad/associations")
    fun list(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = associations.associations(actor, id, workingCopy)

    @PostMapping("/api/v1/objects/{id}/cad/associations")
    @ResponseStatus(HttpStatus.CREATED)
    fun associate(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: com.plm.cad.api.Associate,
    ) = associations.associate(actor, id, request)

    @DeleteMapping("/api/v1/objects/{id}/cad/associations/{associationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun dissociate(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable associationId: UUID,
    ) = associations.dissociate(actor, id, associationId)

    @PostMapping("/api/v1/objects/{id}/cad/build")
    fun build(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: com.plm.cad.api.BuildRequest,
    ) = associations.build(actor, id, request)
}

/** STEP import (roadmap 6.5): the file as the request body. */
@RestController
internal class StepImportController(
    private val imports: com.plm.cad.api.StepImportService,
) {
    @PostMapping("/api/v1/cad/step-import/preview", consumes = [org.springframework.http.MediaType.ALL_VALUE])
    fun preview(
        actor: Actor,
        @RequestParam fileName: String,
        body: java.io.InputStream,
    ) = imports.preview(actor, fileName, body.readNBytes(50 * 1024 * 1024 + 1))

    @PostMapping("/api/v1/cad/step-import", consumes = [org.springframework.http.MediaType.ALL_VALUE])
    fun import(
        actor: Actor,
        @RequestParam fileName: String,
        @RequestParam contextId: UUID,
        @RequestParam(defaultValue = "true") createParts: Boolean,
        @RequestParam(defaultValue = "true") build: Boolean,
        body: java.io.InputStream,
    ) = imports.import(
        actor,
        fileName,
        body.readNBytes(50 * 1024 * 1024 + 1),
        com.plm.cad.api
            .StepImportOptions(contextId, createParts, build),
    )
}

/** Viewables (roadmap 6.6). */
@RestController
internal class ViewableController(
    private val conversions: com.plm.cad.api.ConversionService,
) {
    @GetMapping("/api/v1/objects/{id}/cad/viewable")
    fun viewable(
        actor: Actor,
        @PathVariable id: UUID,
    ) = conversions.viewable(actor, id)

    @PostMapping("/api/v1/objects/{id}/cad/viewable/convert")
    fun convert(
        actor: Actor,
        @PathVariable id: UUID,
    ) = conversions.convert(actor, id)
}
