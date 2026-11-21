package com.plm.versioning.infra.web

import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ShareRequest
import com.plm.versioning.api.SharingService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class SharingController(
    private val sharing: SharingService,
) {
    @GetMapping("/api/v1/objects/{id}/shares")
    fun shares(
        actor: Actor,
        @PathVariable id: UUID,
    ) = sharing.shares(actor, MasterId(id))

    @PostMapping("/api/v1/objects/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    fun share(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: ShareRequest,
    ) = sharing.share(actor, MasterId(id), request)

    @DeleteMapping("/api/v1/objects/{id}/shares/{principalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unshare(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable principalId: String,
    ) = sharing.unshare(actor, MasterId(id), principalId)

    @GetMapping("/api/v1/me/shared-with-me")
    fun sharedWithMe(actor: Actor) = sharing.sharedWithMe(actor)
}
