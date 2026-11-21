package com.plm.extensions.infra.web

import com.plm.extensions.domain.UiExtensions
import com.plm.platform.actor.Actor
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** UI extension slots and extension endpoints (roadmap 5.15). */
@RestController
internal class UiExtensionController(
    private val ui: UiExtensions,
) {
    /** The panels and tabs extensions add to this object's page. */
    @GetMapping("/api/v1/objects/{id}/ui-extensions")
    fun slots(
        actor: Actor,
        @PathVariable id: UUID,
    ) = ui.slots(actor, id)

    /** An extension's own endpoint, forwarded to its service (signed). */
    @RequestMapping(
        "/api/v1/ext/{extensionId}/**",
        method = [RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE],
    )
    fun forward(
        actor: Actor,
        @PathVariable extensionId: String,
        request: RequestEntity<ByteArray>,
    ): ResponseEntity<ByteArray> {
        val prefix = "/api/v1/ext/$extensionId/"
        val path = request.url.rawPath.substringAfter(prefix, "")
        val body = request.body ?: ByteArray(0)
        if (body.size > 1024 * 1024) return ResponseEntity.status(413).build()
        val r =
            ui.forward(
                actor,
                extensionId,
                request.method?.name() ?: "GET",
                path,
                request.url.rawQuery,
                request.headers.contentType?.toString(),
                body,
            )
        return ResponseEntity
            .status(r.status)
            .header("Content-Type", r.contentType)
            .header("X-Content-Type-Options", "nosniff")
            .body(r.body)
    }
}
