package com.plm.collaboration.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

data class Comment(
    val id: UUID,
    val objectId: UUID,
    /** The topic this replies to; null for a topic. */
    val parentId: UUID?,
    val body: String,
    /** Principal ids of the people @mentioned. */
    val mentions: List<String>,
    val author: String,
    val createdAt: Instant,
    val editedAt: Instant?,
    val deleted: Boolean,
    /** Topics only: marked done. */
    val resolved: Boolean,
)

data class PostComment(
    val body: String,
    val parentId: UUID? = null,
)

/**
 * Discussions on objects (roadmap 3.10): anyone who can read the object can read and post. A topic
 * has replies one level deep; `@username` mentions notify that person (if they can read the object),
 * and everyone who took part in a topic hears about replies to it. Authors edit and delete their own
 * comments; site administrators can delete any. Errors: `OBJECT_NOT_FOUND`, `COMMENT_NOT_FOUND`,
 * `INVALID_COMMENT`, `ACCESS_DENIED`.
 */
interface DiscussionService {
    fun comments(
        actor: Actor,
        objectId: UUID,
    ): List<Comment>

    fun post(
        actor: Actor,
        objectId: UUID,
        comment: PostComment,
    ): Comment

    fun edit(
        actor: Actor,
        commentId: UUID,
        body: String,
    ): Comment

    fun delete(
        actor: Actor,
        commentId: UUID,
    )

    fun resolve(
        actor: Actor,
        commentId: UUID,
        resolved: Boolean,
    ): Comment
}

data class CommentPosted(
    val commentId: UUID,
    val objectId: UUID,
    val parentId: UUID?,
    val mentions: List<String>,
) : DomainEvent {
    override val eventType = "collaboration.CommentPosted.v1"
    override val aggregateType = "versioned-object"
    override val aggregateId: UUID = objectId
}
