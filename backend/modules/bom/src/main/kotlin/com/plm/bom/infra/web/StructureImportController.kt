package com.plm.bom.infra.web

import com.plm.bom.api.ImportRow
import com.plm.bom.api.StructureImportService
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.InputStream
import java.util.UUID

internal data class StartImportRequest(
    val fileName: String? = null,
    val rows: List<ImportRow>,
)

/** Structure import (roadmap 4.13): read and check a file, then run it as a background job. */
@RestController
internal class StructureImportController(
    private val imports: StructureImportService,
) {
    /** The file as the request body (CSV or .xlsx): what the import would do, line by line. Changes nothing. */
    @PostMapping("/api/v1/objects/{id}/structure-import/preview", consumes = [MediaType.ALL_VALUE])
    fun preview(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam fileName: String,
        body: InputStream,
    ) = imports.preview(actor, MasterId(id), imports.read(fileName, body.readNBytes(10 * 1024 * 1024 + 1)))

    /** Starts the import of the rows a preview returned; follow it at /api/v1/jobs/{id}. */
    @PostMapping("/api/v1/objects/{id}/structure-import")
    fun start(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: StartImportRequest,
    ) = imports.start(actor, MasterId(id), request.rows, request.fileName)
}
