package com.plm.collaboration.domain

import com.plm.collaboration.api.Comment
import com.plm.collaboration.api.CommentPosted
import com.plm.collaboration.api.DiscussionService
import com.plm.collaboration.api.PostComment
import com.plm.identity.api.PrincipalDirectory
import com.plm.notifications.api.PersonalService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private const val MAX_BODY = 10_000
private val MENTION = Regex("""(?<![\w.])@([A-Za-z0-9._-]{2,64})""")

@Service
@Transactional
internal class DefaultDiscussionService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val directory: PrincipalDirectory,
    private val personal: PersonalService,
    private val events: DomainEvents,
    private val clock: Clock,
) : DiscussionService {
    private val mapper =
        RowMapper { rs, _ ->
            val deleted = rs.getBoolean("deleted")
            Comment(
                rs.getObject("id", UUID::class.java),
                rs.getObject("object_id", UUID::class.java),
                rs.getObject("parent_id", UUID::class.java),
                if (deleted) "" else rs.getString("body"),
                (rs.getArray("mentions").array as Array<*>).map { it.toString() },
                rs.getString("author"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("edited_at")?.toInstant(),
                deleted,
                rs.getBoolean("resolved"),
            )
        }

    @Transactional(readOnly = true)
    override fun comments(
        actor: Actor,
        objectId: UUID,
    ): List<Comment> {
        versioning.get(actor, MasterId(objectId))
        return jdbc
            .sql("select * from comment where tenant_id = :tenant and object_id = :object order by created_at, id")
            .param("tenant", actor.tenant.value)
            .param("object", objectId)
            .query(mapper)
            .list()
    }

    private fun find(
        actor: Actor,
        id: UUID,
    ): Comment {
        val c =
            jdbc
                .sql("select * from comment where tenant_id = :tenant and id = :id")
                .param("tenant", actor.tenant.value)
                .param("id", id)
                .query(mapper)
                .optional()
                .orElseThrow { NotFoundException("COMMENT_NOT_FOUND", "Comment $id does not exist") }
        // Only those who may read the object see its comments.
        versioning.get(actor, MasterId(c.objectId))
        return c
    }

    private fun body(text: String): String {
        val b = text.trim()
        if (b.isEmpty()) throw ValidationException("INVALID_COMMENT", "Write something first")
        if (b.length >
            MAX_BODY
        ) {
            throw ValidationException("INVALID_COMMENT", "A comment is at most $MAX_BODY characters")
        }
        return b
    }

    /** `@alice` → alice's id, for users the directory knows. */
    private fun mentions(
        actor: Actor,
        body: String,
    ): List<String> =
        MENTION
            .findAll(body)
            .map { it.groupValues[1].trimEnd('.') }
            .distinct()
            .mapNotNull { directory.findByUsername(actor, it)?.id?.value }
            .toList()

    override fun post(
        actor: Actor,
        objectId: UUID,
        comment: PostComment,
    ): Comment {
        val obj = versioning.get(actor, MasterId(objectId))
        val body = body(comment.body)
        val parent = comment.parentId?.let { find(actor, it) }
        if (parent != null && (parent.objectId != objectId || parent.parentId != null)) {
            throw ValidationException("INVALID_COMMENT", "Replies go to a topic of the same object")
        }
        val mentioned = mentions(actor, body)
        val id = UuidV7.generate(clock)
        jdbc
            .sql(
                """
                insert into comment (id, tenant_id, object_id, parent_id, body, mentions, author, created_at)
                values (:id, :tenant, :object, :parent, :body, :mentions, :author, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", actor.tenant.value)
            .param("object", objectId)
            .param("parent", parent?.id)
            .param("body", body)
            .param("mentions", mentioned.toTypedArray())
            .param("author", actor.principal.value)
            .param("now", Timestamp.from(clock.instant()))
            .update()
        events.publish(actor, CommentPosted(id, objectId, parent?.id, mentioned))
        notifyPeople(actor, obj, parent, mentioned, body)
        return find(actor, id)
    }

    private fun notifyPeople(
        actor: Actor,
        obj: VersionedObject,
        parent: Comment?,
        mentioned: List<String>,
        body: String,
    ) {
        val participants =
            if (parent == null) {
                emptyList()
            } else {
                jdbc
                    .sql(
                        "select distinct author from comment where tenant_id = :tenant and (id = :topic or parent_id = :topic)",
                    ).param("tenant", actor.tenant.value)
                    .param("topic", parent.id)
                    .query(String::class.java)
                    .list()
            }
        val who = directory.displayName(actor.tenant, actor.principal)
        val excerpt = body.replace(Regex("\\s+"), " ").take(160)
        val told = mutableSetOf(actor.principal.value)

        fun tell(
            principal: String,
            title: String,
        ) {
            if (!told.add(principal)) return
            // Only people who can read the object hear about what's said on it.
            val them = Actor(actor.tenant, PrincipalId(principal))
            if (versioning.list(them, ObjectQuery(ids = listOf(obj.id), limit = 1)).isEmpty()) return
            personal.notify(
                actor.tenant,
                principal,
                title,
                excerpt,
                "/objects/${obj.id.value}?tab=discussion",
                obj.id.value,
                actor.principal.value,
            )
        }
        mentioned.forEach { tell(it, "$who mentioned you on ${obj.number} ${obj.name}") }
        participants.filterNotNull().forEach { tell(it, "$who replied on ${obj.number} ${obj.name}") }
    }

    private fun requireAuthor(
        actor: Actor,
        c: Comment,
        orAdmin: Boolean,
    ) {
        if (c.author == actor.principal.value || (orAdmin && actor.siteAdmin)) return
        throw ForbiddenException("ACCESS_DENIED", "Only its author can change a comment")
    }

    override fun edit(
        actor: Actor,
        commentId: UUID,
        body: String,
    ): Comment {
        val c = find(actor, commentId)
        requireAuthor(actor, c, orAdmin = false)
        if (c.deleted) throw ValidationException("INVALID_COMMENT", "The comment was deleted")
        val b = body(body)
        jdbc
            .sql("update comment set body = :body, mentions = :mentions, edited_at = :now where id = :id")
            .param("id", commentId)
            .param("body", b)
            .param("mentions", mentions(actor, b).toTypedArray())
            .param("now", Timestamp.from(clock.instant()))
            .update()
        return find(actor, commentId)
    }

    override fun delete(
        actor: Actor,
        commentId: UUID,
    ) {
        val c = find(actor, commentId)
        requireAuthor(actor, c, orAdmin = true)
        jdbc
            .sql("update comment set deleted = true, body = '', mentions = '{}' where id = :id")
            .param("id", commentId)
            .update()
    }

    override fun resolve(
        actor: Actor,
        commentId: UUID,
        resolved: Boolean,
    ): Comment {
        val c = find(actor, commentId)
        if (c.parentId != null) throw ValidationException("INVALID_COMMENT", "Only topics can be resolved")
        jdbc
            .sql("update comment set resolved = :resolved where id = :id")
            .param("id", commentId)
            .param("resolved", resolved)
            .update()
        return find(actor, commentId)
    }
}
