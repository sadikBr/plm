package com.plm.collaboration.infra.web

import com.plm.collaboration.api.MarkupKind
import com.plm.collaboration.api.MarkupService
import com.plm.collaboration.api.SaveMarkup
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
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

/** Markups (roadmap 6.10, 6.11): saved views and annotations on models and sheets. */
@RestController
internal class MarkupController(
    private val markups: MarkupService,
) {
    @GetMapping("/api/v1/objects/{id}/markups")
    fun list(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(required = false) kind: MarkupKind?,
    ) = markups.list(actor, id, kind)

    @PostMapping("/api/v1/objects/{id}/markups")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveMarkup,
    ) = markups.create(actor, id, request)

    @PutMapping("/api/v1/markups/{markupId}")
    fun update(
        actor: Actor,
        @PathVariable markupId: UUID,
        @RequestBody request: SaveMarkup,
    ) = markups.update(actor, markupId, request)

    @DeleteMapping("/api/v1/markups/{markupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable markupId: UUID,
    ) = markups.delete(actor, markupId)
}
