package com.plm.packages.infra.web

import com.plm.packages.api.Deliver
import com.plm.packages.api.PackageService
import com.plm.packages.api.SavePackage
import com.plm.platform.actor.Actor
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
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Packages and deliveries (roadmap 9.4). */
@RestController
internal class PackageController(
    private val packages: PackageService,
) {
    @GetMapping("/api/v1/packages")
    fun list(actor: Actor) = packages.list(actor)

    @PostMapping("/api/v1/packages")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody command: SavePackage,
    ) = packages.create(actor, command)

    @GetMapping("/api/v1/packages/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = packages.get(actor, id)

    @PutMapping("/api/v1/packages/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: SavePackage,
    ) = packages.update(actor, id, command)

    @DeleteMapping("/api/v1/packages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = packages.delete(actor, id)

    @GetMapping("/api/v1/packages/{id}/members")
    fun members(
        actor: Actor,
        @PathVariable id: UUID,
    ) = packages.members(actor, id)

    @PostMapping("/api/v1/packages/{id}/deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    fun deliver(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: Deliver,
    ) = packages.deliver(actor, id, command)

    @PostMapping("/api/v1/deliveries/{id}/revoke")
    fun revoke(
        actor: Actor,
        @PathVariable id: UUID,
    ) = packages.revoke(actor, id)

    @GetMapping("/api/v1/deliveries/{id}/downloads")
    fun downloads(
        actor: Actor,
        @PathVariable id: UUID,
    ) = packages.downloads(actor, id)
}

/** The recipient's download: no sign-in, the link is the key; it expires and every use is logged. */
@RestController
internal class DeliveryDownloadController(
    private val packages: PackageService,
) {
    @GetMapping("/api/v1/deliveries/{token}/download", produces = ["application/zip"])
    fun download(
        @PathVariable token: String,
        @RequestHeader("X-Forwarded-For", required = false) forwardedFor: String?,
        @RequestHeader(HttpHeaders.USER_AGENT, required = false) userAgent: String?,
    ): ResponseEntity<ByteArray> {
        val file = packages.download(token, forwardedFor?.substringBefore(',')?.trim(), userAgent)
        return ResponseEntity
            .ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(file.fileName)
                    .build()
                    .toString(),
            ).contentType(MediaType.parseMediaType("application/zip"))
            .body(file.bytes)
    }
}
