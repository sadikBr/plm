package com.plm.content.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.versioning.api.MasterId
import java.io.InputStream
import java.time.Instant
import java.util.UUID

enum class ContentRole { PRIMARY, ATTACHMENT }

enum class ThumbnailStatus { NONE, PENDING, READY, FAILED }

/** A file of an iteration: its primary content or an attachment. */
data class ContentItem(
    val id: UUID,
    val masterId: UUID,
    val iterationId: UUID,
    val role: ContentRole,
    val fileName: String,
    val description: String?,
    val contentType: String,
    val size: Long,
    val sha256: String,
    val thumbnail: ThumbnailStatus,
    val addedAt: Instant,
    val addedBy: String,
    /** True when it came with the iteration it was based on, rather than uploaded to this one. */
    val carriedOver: Boolean,
    /** A PDF made from it (an Office file converted, roadmap 8.4) gives it page previews like a PDF. */
    val previewPdf: Boolean = false,
)

/** Where to send the bytes: PUT them to [uploadUrl] (no other authentication) before [expiresAt]. */
data class UploadTicket(
    val uploadId: UUID,
    val uploadUrl: String,
    val expiresAt: Instant,
    val maxSize: Long,
)

data class RequestUpload(
    val fileName: String,
    val contentType: String? = null,
    val size: Long? = null,
)

data class AttachContent(
    val uploadId: UUID,
    val role: ContentRole = ContentRole.ATTACHMENT,
    val description: String? = null,
)

/** A signed, short-lived address to read a file (or its thumbnail) without other authentication. */
data class FileLink(
    val url: String,
    val expiresAt: Instant,
)

/** A page of a PDF, as an image, for previews where the browser can't show PDFs. */
data class PreviewPage(
    val url: String,
    val expiresAt: Instant,
    /** From 1. */
    val page: Int,
    val pages: Int,
)

/** A file made from a stored file: a viewable, a PDF (roadmap 6.6). */
data class DerivedFile(
    val kind: String,
    val contentType: String,
    val size: Long,
    val createdAt: Instant,
)

/**
 * Document content (roadmap 3.2): files kept per iteration, in the vault: one primary file and any
 * number of attachments, on documents only (`CONTENT_NOT_ALLOWED` elsewhere). Reading needs read access
 * to the object; changing needs the object checked out to you (content goes to your working copy,
 * and becomes the object's at check-in). Check-out and revise carry the content forward.
 *
 * Error codes: `UPLOAD_NOT_FOUND`, `UPLOAD_NOT_RECEIVED`, `UPLOAD_EXPIRED`, `UPLOAD_TOO_LARGE`,
 * `INVALID_FILE_NAME`, `NOT_CHECKED_OUT`, `CONTENT_NOT_FOUND`, `NO_THUMBNAIL`.
 */
interface ContentService {
    /** One file, if the actor may read its object. @throws com.plm.platform.error.NotFoundException `CONTENT_NOT_FOUND` */
    fun item(
        actor: Actor,
        contentId: UUID,
    ): ContentItem

    fun requestUpload(
        actor: Actor,
        request: RequestUpload,
    ): UploadTicket

    /** The content of an iteration: the latest by default, the actor's working copy with [workingCopy]. */
    fun list(
        actor: Actor,
        objectId: MasterId,
        iterationId: UUID? = null,
        workingCopy: Boolean = false,
    ): List<ContentItem>

    fun attach(
        actor: Actor,
        objectId: MasterId,
        request: AttachContent,
    ): ContentItem

    fun rename(
        actor: Actor,
        objectId: MasterId,
        contentId: UUID,
        fileName: String,
        description: String?,
    ): ContentItem

    fun remove(
        actor: Actor,
        objectId: MasterId,
        contentId: UUID,
    )

    /** [inline]: for showing in the browser (previews) rather than saving. */
    fun download(
        actor: Actor,
        contentId: UUID,
        inline: Boolean = false,
    ): FileLink

    fun thumbnail(
        actor: Actor,
        contentId: UUID,
    ): FileLink

    /** A PDF's page [page] (from 1) as a PNG: rendered the first time, kept afterwards. */
    fun previewPage(
        actor: Actor,
        contentId: UUID,
        page: Int,
    ): PreviewPage

    /**
     * Copies the files of [from]'s latest iteration to [to]'s first iteration (Save As, new from
     * a template): the same stored files, new entries. [to] must be new: created by the actor, at
     * its first iteration, with no files yet. Returns how many were copied.
     */
    fun copyLatest(
        actor: Actor,
        from: MasterId,
        to: MasterId,
    ): Int

    /** Bytes of a file the actor can read, for other modules (compare, templates, extensions). */
    fun open(
        actor: Actor,
        contentId: UUID,
    ): InputStream

    /**
     * Stores [bytes] as a file and attaches it to the actor's working copy of [objectId]: for
     * server-side producers (document templates, jobs, extensions).
     */
    fun store(
        actor: Actor,
        objectId: MasterId,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        role: ContentRole,
        description: String? = null,
    ): ContentItem

    /** The file of [kind] made from [contentId]'s file, if there is one. Needs read access. */
    fun derived(
        actor: Actor,
        contentId: UUID,
        kind: String,
    ): DerivedFile?

    /** A link to it, named [fileName]. */
    fun derivedLink(
        actor: Actor,
        contentId: UUID,
        kind: String,
        fileName: String,
        inline: Boolean = true,
    ): FileLink

    /**
     * Keeps [file] as the file of [kind] made from [contentId]'s file (replacing an earlier one):
     * for server-side producers (conversion workers). No access check.
     */
    fun putDerived(
        contentId: UUID,
        kind: String,
        contentType: String,
        file: java.nio.file.Path,
    ): DerivedFile
}

data class ContentChanged(
    val objectId: UUID,
    val iterationId: UUID,
    val contentId: UUID,
    val fileName: String,
    val role: ContentRole,
    /** ADDED, RENAMED, REMOVED */
    val change: String,
) : DomainEvent {
    override val eventType = "content.ContentChanged.v1"
    override val aggregateType = "versioned-object"
    override val aggregateId: UUID = objectId
}
