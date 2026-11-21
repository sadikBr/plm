package com.plm.change.infra.web

import com.plm.change.api.AddChangeLink
import com.plm.change.api.ChangeFrom
import com.plm.change.api.ChangeService
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class Created(
    val id: UUID,
)

/** Change objects' relations (roadmap 5.8). */
@RestController
internal class ChangeController(
    private val changes: ChangeService,
) {
    @GetMapping("/api/v1/changes/{id}")
    fun view(
        actor: Actor,
        @PathVariable id: UUID,
    ) = changes.view(actor, id)

    @PostMapping("/api/v1/changes/{id}/links")
    fun link(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: AddChangeLink,
    ) = changes.link(actor, id, request)

    @DeleteMapping("/api/v1/changes/{id}/links/{linkId}")
    fun unlink(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable linkId: UUID,
    ) = changes.unlink(actor, id, linkId)

    /** The next change object of the chain: a request from a report, a notice from a request, a task of a notice. */
    @PostMapping("/api/v1/changes/from")
    fun createFrom(
        actor: Actor,
        @RequestBody request: ChangeFrom,
    ) = Created(changes.createFrom(actor, request))

    /** The changes a part or document is affected by, results from, or is an end item of. */
    @GetMapping("/api/v1/objects/{id}/changes")
    fun changesOf(
        actor: Actor,
        @PathVariable id: UUID,
    ) = changes.changesOf(actor, id)
}
