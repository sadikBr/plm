package com.plm.content.infra.web

import com.plm.content.api.AttachContent
import com.plm.content.api.ContentService
import com.plm.content.api.RequestUpload
import com.plm.content.domain.DefaultContentService
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import org.springframework.core.io.InputStreamResource
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
import java.io.InputStream
import java.util.UUID

@RestController
internal class ContentController(
    private val content: ContentService,
    private val vault: DefaultContentService,
) {
    /** Step 1 of an upload: where to send the bytes. */
    @PostMapping("/api/v1/vault/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    fun requestUpload(
        actor: Actor,
        @RequestBody request: RequestUpload,
    ) = content.requestUpload(actor, request)

    /**
     * Step 2: the bytes, as the request body, to the signed URL from step 1. Needs no other
     * authentication; the URL is the permission.
     */
    @PutMapping("/api/v1/vault/uploads/{id}", consumes = [MediaType.ALL_VALUE])
    fun receive(
        @PathVariable id: UUID,
        @RequestParam exp: String,
        @RequestParam sig: String,
        body: InputStream,
    ) = vault.receive(id, exp, sig, body)

    /** A signed file link (from download or thumbnail): the file itself. */
    @GetMapping("/api/v1/vault/files")
    fun file(
        @RequestParam k: String,
        @RequestParam n: String,
        @RequestParam t: String,
        @RequestParam d: String,
        @RequestParam exp: String,
        @RequestParam sig: String,
    ): ResponseEntity<InputStreamResource> {
        val (stream, size) = vault.serve(k, n, t, d, exp, sig)
        val disposition =
            (if (d == "inline") ContentDisposition.inline() else ContentDisposition.attachment())
                .filename(n, Charsets.UTF_8)
                .build()
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .header("X-Content-Type-Options", "nosniff")
            // Files are shown as they are: nothing in them runs (no scripts), and only our pages frame them.
            .header(
                "Content-Security-Policy",
                "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; frame-ancestors 'self'",
            ).contentType(runCatching { MediaType.parseMediaType(t) }.getOrDefault(MediaType.APPLICATION_OCTET_STREAM))
            .apply { if (size != null) contentLength(size) }
            .body(InputStreamResource(stream))
    }

    /** The files of an object: its latest iteration's, another's with `iterationId`, or your working copy's. */
    @GetMapping("/api/v1/objects/{id}/content")
    fun list(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(required = false) iterationId: UUID?,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = content.list(actor, MasterId(id), iterationId, workingCopy)

    /** Step 3: attach an upload to your working copy. */
    @PostMapping("/api/v1/objects/{id}/content")
    @ResponseStatus(HttpStatus.CREATED)
    fun attach(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: AttachContent,
    ) = content.attach(actor, MasterId(id), request)

    @PutMapping("/api/v1/objects/{id}/content/{contentId}")
    fun rename(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable contentId: UUID,
        @RequestBody request: RenameContentRequest,
    ) = content.rename(actor, MasterId(id), contentId, request.fileName, request.description)

    @DeleteMapping("/api/v1/objects/{id}/content/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable contentId: UUID,
    ) = content.remove(actor, MasterId(id), contentId)

    /** A link to download a file (or, with `inline`, to show it in the browser). */
    @GetMapping("/api/v1/content/{contentId}/link")
    fun link(
        actor: Actor,
        @PathVariable contentId: UUID,
        @RequestParam(defaultValue = "false") inline: Boolean,
    ) = content.download(actor, contentId, inline)

    /** A PDF page as an image, for previews. */
    @GetMapping("/api/v1/content/{contentId}/pages/{page}")
    fun page(
        actor: Actor,
        @PathVariable contentId: UUID,
        @PathVariable page: Int,
    ) = content.previewPage(actor, contentId, page)

    @GetMapping("/api/v1/content/{contentId}/thumbnail")
    fun thumbnail(
        actor: Actor,
        @PathVariable contentId: UUID,
    ) = content.thumbnail(actor, contentId)
}

internal data class RenameContentRequest(
    val fileName: String,
    val description: String? = null,
)
