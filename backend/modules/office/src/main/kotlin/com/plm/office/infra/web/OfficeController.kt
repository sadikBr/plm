package com.plm.office.infra.web

import com.plm.office.api.OfficeService
import com.plm.office.api.PropertyMapping
import com.plm.platform.actor.Actor
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class OfficeStatus(
    /** LibreOffice is installed: Office files get PDF previews. */
    val previews: Boolean,
)

/** Office files (roadmap 8.4): property mappings, downloads with properties written in. */
@RestController
internal class OfficeController(
    private val office: OfficeService,
) {
    @GetMapping("/api/v1/office/status")
    fun status() = OfficeStatus(office.previews())

    @GetMapping("/api/v1/office/mappings/{typeId}")
    fun mappings(
        actor: Actor,
        @PathVariable typeId: UUID,
    ) = office.mappings(actor, typeId)

    @PutMapping("/api/v1/office/mappings/{typeId}")
    fun setMappings(
        actor: Actor,
        @PathVariable typeId: UUID,
        @RequestBody mappings: List<PropertyMapping>,
    ) = office.setMappings(actor, typeId, mappings)

    @GetMapping("/api/v1/office/content/{contentId}/properties")
    fun properties(
        actor: Actor,
        @PathVariable contentId: UUID,
    ) = office.properties(actor, contentId)

    /** The file, with its object's attributes written into its properties. */
    @GetMapping("/api/v1/office/content/{contentId}")
    fun download(
        actor: Actor,
        @PathVariable contentId: UUID,
    ): ResponseEntity<ByteArray> {
        val file = office.download(actor, contentId)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(file.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(file.fileName, Charsets.UTF_8)
                    .build()
                    .toString(),
            ).body(file.bytes)
    }
}
