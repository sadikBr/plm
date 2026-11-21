package com.plm.collaboration.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

enum class MarkupKind { MODEL, SHEET }

/**
 * A saved view with annotations (roadmap 6.10, 6.11): on a version's 3D model ([MarkupKind.MODEL])
 * or on page [page] of its file [contentId] ([MarkupKind.SHEET]). [view] and [shapes] are the
 * viewer's own: a camera, section and hidden parts, or a zoom; pins and notes, or 2D shapes in page
 * coordinates. Its discussion is the topic [topicId] of the object's discussion.
 */
data class Markup(
    val id: UUID,
    val objectId: UUID,
    val iterationId: UUID,
    val version: String,
    val kind: MarkupKind,
    val contentId: UUID?,
    val page: Int?,
    val title: String,
    val view: Map<String, Any?>,
    val shapes: List<Map<String, Any?>>,
    val topicId: UUID?,
    val author: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SaveMarkup(
    val kind: MarkupKind = MarkupKind.MODEL,
    val title: String,
    val view: Map<String, Any?> = emptyMap(),
    val shapes: List<Map<String, Any?>> = emptyList(),
    val contentId: UUID? = null,
    val page: Int? = null,
    /** Opens the markup's discussion topic. */
    val note: String? = null,
)

/**
 * Markups (roadmap 6.10, 6.11): anyone who can read an object can mark up its latest version, and
 * see everyone's markups of every version; each opens a topic in the object's discussion. Authors
 * change and delete their own (site administrators delete any). Errors: `MARKUP_NOT_FOUND`,
 * `INVALID_MARKUP`, `ACCESS_DENIED`, `OBJECT_NOT_FOUND`.
 */
interface MarkupService {
    fun list(
        actor: Actor,
        objectId: UUID,
        kind: MarkupKind? = null,
    ): List<Markup>

    fun create(
        actor: Actor,
        objectId: UUID,
        request: SaveMarkup,
    ): Markup

    /** Changes the title, view and shapes; the version and topic stay. */
    fun update(
        actor: Actor,
        markupId: UUID,
        request: SaveMarkup,
    ): Markup

    fun delete(
        actor: Actor,
        markupId: UUID,
    )
}
