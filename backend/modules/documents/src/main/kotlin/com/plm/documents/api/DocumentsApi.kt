package com.plm.documents.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersionedObject
import java.time.Instant
import java.util.UUID

data class DocumentTemplate(
    val id: UUID,
    val name: String,
    val description: String?,
    /** The template object, and what's taken from its latest version. */
    val objectId: UUID,
    val number: String,
    val version: String,
    val typeId: UUID,
    /** The latest version's primary file, if any. */
    val fileName: String?,
    val organizationId: UUID?,
    val active: Boolean,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class TemplateSettings(
    val objectId: UUID,
    val name: String,
    val description: String? = null,
    val organizationId: UUID? = null,
    val active: Boolean = true,
)

data class NewFromTemplate(
    val templateId: UUID,
    val contextId: UUID,
    val name: String,
    /** On top of the template's own values. */
    val attributes: Map<String, Any?> = emptyMap(),
    val number: String? = null,
)

data class SaveAs(
    val name: String,
    /** Defaults to the original's context. */
    val contextId: UUID? = null,
    val number: String? = null,
    /** Copy its files too (default). */
    val withFiles: Boolean = true,
)

/**
 * Documents (roadmap 3.3): templates new documents start from, Save As, compare.
 *
 * Templates are ordinary objects registered as templates by site administrators; a new document
 * gets the template's type, attribute values and files (its latest version's). Error codes:
 * `TEMPLATE_NOT_FOUND`, `DUPLICATE_TEMPLATE`, `INVALID_TEMPLATE`, `ADMIN_REQUIRED`.
 */
interface DocumentService {
    /** Templates usable in [contextId] (site-wide ones and its organization's), for [typeId] if given. */
    fun templates(
        actor: Actor,
        contextId: UUID? = null,
        typeId: UUID? = null,
    ): List<DocumentTemplate>

    fun createTemplate(
        actor: Actor,
        settings: TemplateSettings,
    ): DocumentTemplate

    fun updateTemplate(
        actor: Actor,
        id: UUID,
        settings: TemplateSettings,
    ): DocumentTemplate

    fun deleteTemplate(
        actor: Actor,
        id: UUID,
    )

    fun newFromTemplate(
        actor: Actor,
        request: NewFromTemplate,
    ): VersionedObject

    /** A copy with a new number: the latest version's attributes and (by default) files. */
    fun saveAs(
        actor: Actor,
        objectId: MasterId,
        request: SaveAs,
    ): VersionedObject
}

/** One side of a comparison: an iteration of an object; the latest when [iterationId] is null. */
data class CompareSide(
    val objectId: UUID,
    val iterationId: UUID? = null,
)

data class ComparedVersion(
    val objectId: UUID,
    val iterationId: UUID,
    val number: String,
    val name: String,
    val version: String,
    val state: String,
    val typeId: UUID,
)

data class AttributeDifference(
    val name: String,
    val left: Any?,
    val right: Any?,
    val changed: Boolean,
)

enum class FileChange { SAME, CHANGED, ADDED, REMOVED }

data class FileDifference(
    val role: String,
    val fileName: String,
    val change: FileChange,
    val leftContentId: UUID?,
    val rightContentId: UUID?,
    val leftSize: Long?,
    val rightSize: Long?,
)

enum class LineChange { SAME, ADDED, REMOVED }

data class DiffLine(
    val change: LineChange,
    val text: String,
    val leftLine: Int?,
    val rightLine: Int?,
)

data class TextDifference(
    val fileName: String,
    val lines: List<DiffLine>,
    val added: Int,
    val removed: Int,
    /** Too long or not text: no line diff. */
    val skipped: String?,
)

data class Comparison(
    val left: ComparedVersion,
    val right: ComparedVersion,
    val attributes: List<AttributeDifference>,
    val files: List<FileDifference>,
    /** Line by line, for the primary files when both are text. */
    val primaryText: TextDifference?,
)

/** Compares two versions: of one object, or of two objects. Needs read access to both. */
interface CompareService {
    fun compare(
        actor: Actor,
        left: CompareSide,
        right: CompareSide,
    ): Comparison
}

data class DocumentTemplateChanged(
    val templateId: UUID,
    val name: String,
    /** CREATED, UPDATED, DELETED */
    val change: String,
) : DomainEvent {
    override val eventType = "documents.DocumentTemplateChanged.v1"
    override val aggregateType = "document-template"
    override val aggregateId: UUID = templateId
}

/** A new object made from another: by Save As or from a template. */
data class ObjectCopied(
    val objectId: UUID,
    val number: String,
    val sourceObjectId: UUID,
    val sourceNumber: String,
    val sourceVersion: String,
    /** SAVE_AS or TEMPLATE */
    val how: String,
    val files: Int,
) : DomainEvent {
    override val eventType = "documents.ObjectCopied.v1"
    override val aggregateType = "versioned-object"
    override val aggregateId: UUID = objectId
}
