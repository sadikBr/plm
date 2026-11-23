package com.plm.ecad.infra.web

import com.plm.ecad.api.EcadImportOptions
import com.plm.ecad.api.EcadService
import com.plm.ecad.api.ProjectFile
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/** Electrical CAD (roadmap 8.7): a KiCad project's files, sent together (multipart). */
@RestController
internal class EcadController(
    private val ecad: EcadService,
) {
    private fun files(parts: List<MultipartFile>) = parts.map { ProjectFile(it.originalFilename ?: it.name, it.bytes) }

    @PostMapping("/api/v1/ecad/kicad/preview", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun preview(
        actor: Actor,
        @RequestParam("files") parts: List<MultipartFile>,
    ) = ecad.preview(actor, files(parts))

    @PostMapping("/api/v1/ecad/kicad/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun import(
        actor: Actor,
        @RequestParam("files") parts: List<MultipartFile>,
        @RequestParam contextId: UUID,
        @RequestParam(defaultValue = "true") createParts: Boolean,
    ) = ecad.import(actor, files(parts), EcadImportOptions(contextId, createParts))

    @GetMapping("/api/v1/objects/{id}/ecad/bom-check")
    fun check(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ecad.check(actor, id)
}
