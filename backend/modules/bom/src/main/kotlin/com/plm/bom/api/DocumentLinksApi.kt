package com.plm.bom.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.versioning.api.MasterId
import java.time.Instant
import java.util.UUID

enum class DocumentLinkKind {
    /** A specific version of the document describes the part. */
    DESCRIBED_BY,

    /** The document, always at its latest version. */
    REFERENCES,
}

/** A part's link to a document, from the part's side (roadmap 4.7). */
data class DocumentLink(
    val id: UUID,
    val kind: DocumentLinkKind,
    /** The document: for DESCRIBED_BY at the linked iteration, for REFERENCES at its latest. */
    val document: UsedPart,
    /** DESCRIBED_BY: the document has newer iterations than the linked one. */
    val newerExists: Boolean,
    val createdAt: Instant,
    val createdBy: String,
)

/** A part linking to a document, from the document's side. */
data class LinkingPart(
    val kind: DocumentLinkKind,
    val part: UsedPart,
    /** DESCRIBED_BY: which version of the document the part's latest iteration points to. */
    val documentVersion: String?,
)

data class DocumentLinkRule(
    val id: UUID,
    val partTypeId: UUID,
    val documentTypeId: UUID,
    val kind: DocumentLinkKind,
)

/**
 * Part-document links (roadmap 4.7). They belong to the part's iteration: edited on its working
 * copy, frozen at check-in, carried forward on check-out and revise. Administrators may limit which
 * document types each part type links to, and how ([rules]); without a rule for a part type (or its
 * ancestors) anything goes. Errors: `NOT_A_PART`, `NOT_A_DOCUMENT`, `LINK_NOT_ALLOWED`,
 * `DUPLICATE_LINK`, `LINK_NOT_FOUND`, `NOT_CHECKED_OUT`.
 */
interface DocumentLinkService {
    /** The part's links, at its latest iteration, [iterationId], or the caller's working copy. */
    fun links(
        actor: Actor,
        partId: MasterId,
        iterationId: UUID? = null,
        workingCopy: Boolean = false,
    ): List<DocumentLink>

    /** Parts whose latest iteration links to [documentId]. */
    fun linkingParts(
        actor: Actor,
        documentId: MasterId,
    ): List<LinkingPart>

    fun link(
        actor: Actor,
        partId: MasterId,
        kind: DocumentLinkKind,
        documentId: UUID,
    ): DocumentLink

    /** DESCRIBED_BY: points the link at the document's latest iteration. */
    fun updateToLatest(
        actor: Actor,
        partId: MasterId,
        linkId: UUID,
    ): DocumentLink

    fun unlink(
        actor: Actor,
        partId: MasterId,
        linkId: UUID,
    )

    fun rules(actor: Actor): List<DocumentLinkRule>

    /** Site administrators. */
    fun addRule(
        actor: Actor,
        partTypeId: UUID,
        documentTypeId: UUID,
        kind: DocumentLinkKind,
    ): DocumentLinkRule

    fun removeRule(
        actor: Actor,
        ruleId: UUID,
    )
}

data class DocumentLinksChanged(
    val objectId: UUID,
    val iterationId: UUID,
    /** LINKED, UPDATED, UNLINKED */
    val change: String,
    val kind: DocumentLinkKind,
    val documentId: UUID,
    val documentNumber: String?,
) : DomainEvent {
    override val eventType = "bom.DocumentLinksChanged.v1"
    override val aggregateType = "versioned-object"
    override val aggregateId: UUID = objectId
}
