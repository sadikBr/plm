package com.plm.content.infra.web

import com.plm.content.api.FileSiteService
import com.plm.content.api.SaveFileSite
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class PreferredSite(
    /** Null: the main vault. */
    val siteId: UUID?,
)

/** File sites (roadmap 9.10). */
@RestController
internal class FileSiteController(
    private val sites: FileSiteService,
) {
    @GetMapping("/api/v1/file-sites")
    fun list(actor: Actor) = sites.sites(actor)

    @PostMapping("/api/v1/file-sites")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody command: SaveFileSite,
    ) = sites.create(actor, command)

    @PutMapping("/api/v1/file-sites/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: SaveFileSite,
    ) = sites.update(actor, id, command)

    @DeleteMapping("/api/v1/file-sites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = sites.delete(actor, id)

    @PostMapping("/api/v1/file-sites/{id}/replicate")
    fun replicate(
        actor: Actor,
        @PathVariable id: UUID,
    ) = mapOf("jobId" to sites.replicateAll(actor, id))

    @GetMapping("/api/v1/me/file-site")
    fun preferred(actor: Actor) = mapOf("siteId" to sites.preferred(actor), "choices" to sites.choices(actor))

    @PutMapping("/api/v1/me/file-site")
    fun setPreferred(
        actor: Actor,
        @RequestBody body: PreferredSite,
    ) = sites.setPreferred(actor, body.siteId).let { mapOf("siteId" to body.siteId) }
}
