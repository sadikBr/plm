package com.plm.publishing.infra.web

import com.plm.platform.actor.Actor
import com.plm.publishing.api.PdfFile
import com.plm.publishing.api.PublishRuleSettings
import com.plm.publishing.api.PublishSettings
import com.plm.publishing.api.PublishingService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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

/** Publishing, watermarks and data sheets (roadmap 6.7). */
@RestController
internal class PublishingController(
    private val publishing: PublishingService,
) {
    private fun pdf(
        f: PdfFile,
        inline: Boolean,
    ): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                (if (inline) ContentDisposition.inline() else ContentDisposition.attachment())
                    .filename(
                        f.fileName,
                        Charsets.UTF_8,
                    ).build()
                    .toString(),
            ).header("Cache-Control", "no-store")
            .body(f.bytes)

    @GetMapping("/api/v1/publish-rules")
    fun rules(actor: Actor) = publishing.rules(actor)

    @PostMapping("/api/v1/publish-rules")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody settings: PublishRuleSettings,
    ) = publishing.saveRule(actor, null, settings)

    @PutMapping("/api/v1/publish-rules/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody settings: PublishRuleSettings,
    ) = publishing.saveRule(actor, id, settings)

    @DeleteMapping("/api/v1/publish-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = publishing.deleteRule(actor, id)

    @GetMapping("/api/v1/publish-settings")
    fun settings(actor: Actor) = publishing.settings(actor)

    @PutMapping("/api/v1/publish-settings")
    fun saveSettings(
        actor: Actor,
        @RequestBody settings: PublishSettings,
    ) = publishing.saveSettings(actor, settings)

    @GetMapping("/api/v1/objects/{id}/publications")
    fun publications(
        actor: Actor,
        @PathVariable id: UUID,
    ) = publishing.publications(actor, id)

    @PostMapping("/api/v1/objects/{id}/publications")
    @ResponseStatus(HttpStatus.CREATED)
    fun publish(
        actor: Actor,
        @PathVariable id: UUID,
    ) = publishing.publish(actor, id)

    @GetMapping("/api/v1/publications/{id}/pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun download(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") inline: Boolean,
    ) = pdf(publishing.download(actor, id), inline)

    @GetMapping("/api/v1/objects/{id}/datasheet.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun datasheet(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") inline: Boolean,
    ) = pdf(publishing.datasheet(actor, id), inline)

    @GetMapping("/api/v1/content/{id}/watermarked", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun watermarked(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") inline: Boolean,
    ) = pdf(publishing.watermarked(actor, id), inline)
}
