package com.plm.bulk.infra.web

import com.plm.bulk.api.BulkLoadService
import com.plm.bulk.api.LoadFile
import com.plm.platform.actor.Actor
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/** Bulk load (roadmap 9.2): the dry run, the load itself (a job) and a template to fill in. */
@RestController
internal class BulkLoadController(
    private val loads: BulkLoadService,
) {
    private fun file(f: MultipartFile) = LoadFile(f.originalFilename ?: f.name, f.bytes)

    @PostMapping("/api/v1/bulk-loads/preview", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun preview(
        actor: Actor,
        @RequestParam contextId: UUID,
        @RequestPart("sheet") sheet: MultipartFile,
        @RequestPart("files", required = false) files: MultipartFile?,
    ) = loads.preview(actor, contextId, file(sheet), files?.let(::file))

    @PostMapping("/api/v1/bulk-loads", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(
        actor: Actor,
        @RequestParam contextId: UUID,
        @RequestPart("sheet") sheet: MultipartFile,
        @RequestPart("files", required = false) files: MultipartFile?,
    ) = loads.start(actor, contextId, file(sheet), files?.let(::file))

    @GetMapping("/api/v1/bulk-loads/template", produces = ["text/csv"])
    fun template(
        actor: Actor,
        @RequestParam typeId: UUID,
    ): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bulk-load.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(loads.template(actor, typeId).toByteArray())
}
