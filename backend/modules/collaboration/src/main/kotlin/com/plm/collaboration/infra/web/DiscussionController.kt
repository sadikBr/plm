package com.plm.collaboration.infra.web

import com.plm.collaboration.api.DiscussionService
import com.plm.collaboration.api.PostComment
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

data class EditComment(
    val body: String,
)

data class ResolveComment(
    val resolved: Boolean,
)

@RestController
internal class DiscussionController(
    private val discussions: DiscussionService,
) {
    @GetMapping("/api/v1/objects/{id}/comments")
    fun comments(
        actor: Actor,
        @PathVariable id: UUID,
    ) = discussions.comments(actor, id)

    @PostMapping("/api/v1/objects/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun post(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody comment: PostComment,
    ) = discussions.post(actor, id, comment)

    @PutMapping("/api/v1/comments/{commentId}")
    fun edit(
        actor: Actor,
        @PathVariable commentId: UUID,
        @RequestBody request: EditComment,
    ) = discussions.edit(actor, commentId, request.body)

    @DeleteMapping("/api/v1/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable commentId: UUID,
    ) = discussions.delete(actor, commentId)

    @PutMapping("/api/v1/comments/{commentId}/resolved")
    fun resolve(
        actor: Actor,
        @PathVariable commentId: UUID,
        @RequestBody request: ResolveComment,
    ) = discussions.resolve(actor, commentId, request.resolved)
}
