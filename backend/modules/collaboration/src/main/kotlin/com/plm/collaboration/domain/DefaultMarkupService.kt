package com.plm.collaboration.domain

import com.plm.collaboration.api.DiscussionService
import com.plm.collaboration.api.Markup
import com.plm.collaboration.api.MarkupKind
import com.plm.collaboration.api.MarkupService
import com.plm.collaboration.api.PostComment
import com.plm.collaboration.api.SaveMarkup
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private const val MAX_JSON = 200_000
private const val MAX_TITLE = 200

@Service
@Transactional
internal class DefaultMarkupService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val discussions: DiscussionService,
    private val clock: Clock,
) : MarkupService {
    private val json = JsonMapper.builder().build()
    private val mapType = object : TypeReference<Map<String, Any?>>() {}
    private val listType = object : TypeReference<List<Map<String, Any?>>>() {}

    private val mapper =
        RowMapper { rs, _ ->
            Markup(
                rs.getObject("id", UUID::class.java),
                rs.getObject("object_id", UUID::class.java),
                rs.getObject("iteration_id", UUID::class.java),
                rs.getString("version"),
                MarkupKind.valueOf(rs.getString("kind")),
                rs.getObject("content_id", UUID::class.java),
                rs.getObject("page") as Int?,
                rs.getString("title"),
                json.readValue(rs.getString("view"), mapType),
                json.readValue(rs.getString("shapes"), listType),
                rs.getObject("topic_id", UUID::class.java),
                rs.getString("author"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
            )
        }

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        objectId: UUID,
        kind: MarkupKind?,
    ): List<Markup> {
        versioning.get(actor, MasterId(objectId))
        return jdbc
            .sql(
                """
                select * from markup where tenant_id = :tenant and object_id = :object
                and (cast(:kind as text) is null or kind = :kind) order by created_at, id
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("object", objectId)
            .param("kind", kind?.name)
            .query(mapper)
            .list()
    }

    private fun find(
        actor: Actor,
        id: UUID,
    ): Markup {
        val m =
            jdbc
                .sql("select * from markup where tenant_id = :tenant and id = :id")
                .param("tenant", actor.tenant.value)
                .param("id", id)
                .query(mapper)
                .optional()
                .orElseThrow { NotFoundException("MARKUP_NOT_FOUND", "Markup $id does not exist") }
        versioning.get(actor, MasterId(m.objectId))
        return m
    }

    private fun check(request: SaveMarkup): Pair<String, String> {
        if (request.title.isBlank()) throw ValidationException("INVALID_MARKUP", "Give the markup a title")
        if (request.title.length >
            MAX_TITLE
        ) {
            throw ValidationException("INVALID_MARKUP", "A title is at most $MAX_TITLE characters")
        }
        if (request.kind == MarkupKind.SHEET && (request.contentId == null || (request.page ?: 0) < 1)) {
            throw ValidationException("INVALID_MARKUP", "A sheet markup is on a page (from 1) of a file")
        }
        val view = json.writeValueAsString(request.view)
        val shapes = json.writeValueAsString(request.shapes)
        if (view.length + shapes.length >
            MAX_JSON
        ) {
            throw ValidationException("INVALID_MARKUP", "The markup is too large")
        }
        return view to shapes
    }

    override fun create(
        actor: Actor,
        objectId: UUID,
        request: SaveMarkup,
    ): Markup {
        val obj = versioning.get(actor, MasterId(objectId))
        val (view, shapes) = check(request)
        if (request.contentId != null) {
            val ours =
                jdbc
                    .sql("select count(*) from content_item where id = :id and master_id = :object")
                    .param("id", request.contentId)
                    .param("object", objectId)
                    .query(Long::class.java)
                    .single() > 0
            if (!ours) throw ValidationException("INVALID_MARKUP", "The file is not one of this object's")
        }
        val where = if (request.kind == MarkupKind.SHEET) " (page ${request.page})" else ""
        val note = request.note?.trim().orEmpty()
        val topic =
            discussions.post(
                actor,
                objectId,
                PostComment(
                    "Markup “${request.title.trim()}” on ${obj.latest.version}$where" +
                        if (note.isEmpty()) "" else "\n\n$note",
                ),
            )
        val id = UuidV7.generate(clock)
        val now = Timestamp.from(clock.instant())
        jdbc
            .sql(
                """
                insert into markup (id, tenant_id, object_id, iteration_id, version, kind, content_id, page, title,
                                    view, shapes, topic_id, author, created_at, updated_at)
                values (:id, :tenant, :object, :iteration, :version, :kind, :content, :page, :title,
                        cast(:view as jsonb), cast(:shapes as jsonb), :topic, :author, :now, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", actor.tenant.value)
            .param("object", objectId)
            .param("iteration", obj.latest.id.value)
            .param("version", obj.latest.version)
            .param("kind", request.kind.name)
            .param("content", request.contentId)
            .param("page", request.page)
            .param("title", request.title.trim())
            .param("view", view)
            .param("shapes", shapes)
            .param("topic", topic.id)
            .param("author", actor.principal.value)
            .param("now", now)
            .update()
        return find(actor, id)
    }

    private fun requireAuthor(
        actor: Actor,
        m: Markup,
        orAdmin: Boolean,
    ) {
        if (m.author == actor.principal.value || (orAdmin && actor.siteAdmin)) return
        throw ForbiddenException("ACCESS_DENIED", "Only its author can change a markup")
    }

    override fun update(
        actor: Actor,
        markupId: UUID,
        request: SaveMarkup,
    ): Markup {
        val m = find(actor, markupId)
        requireAuthor(actor, m, orAdmin = false)
        val (view, shapes) = check(request.copy(kind = m.kind, contentId = m.contentId, page = m.page))
        jdbc
            .sql(
                """
                update markup set title = :title, view = cast(:view as jsonb), shapes = cast(:shapes as jsonb),
                updated_at = :now where id = :id
                """.trimIndent(),
            ).param("id", markupId)
            .param("title", request.title.trim())
            .param("view", view)
            .param("shapes", shapes)
            .param("now", Timestamp.from(clock.instant()))
            .update()
        return find(actor, markupId)
    }

    override fun delete(
        actor: Actor,
        markupId: UUID,
    ) {
        val m = find(actor, markupId)
        requireAuthor(actor, m, orAdmin = true)
        // The discussion stays, as said.
        jdbc.sql("delete from markup where id = :id").param("id", markupId).update()
    }
}
