package com.plm.standards.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** A STEP AP242 export: a zip with the structure file and the CAD files it references. */
class StepExport(
    val fileName: String,
    val bytes: ByteArray,
    val products: Int,
    val occurrences: Int,
    val files: Int,
)

/** A link from an object to a resource in another tool (roadmap 9.6). */
data class ExternalLink(
    val id: UUID,
    val objectId: UUID,
    /** An OSLC property: see [ExternalLinkTypes]. */
    val linkType: String,
    val url: String,
    val title: String?,
    val createdAt: Instant,
    val createdBy: String,
)

data class AddExternalLink(
    val linkType: String,
    val url: String,
    val title: String? = null,
)

data class ExternalLinkType(
    val key: String,
    val label: String,
)

object ExternalLinkTypes {
    val ALL =
        listOf(
            ExternalLinkType("oslc_rm:implementsRequirement", "Implements requirement"),
            ExternalLinkType("oslc_rm:satisfiesRequirement", "Satisfies requirement"),
            ExternalLinkType("oslc_qm:testedByTestCase", "Tested by test case"),
            ExternalLinkType("oslc_qm:validatedByTestResult", "Validated by test result"),
            ExternalLinkType("oslc_cm:relatedChangeRequest", "Related change request"),
            ExternalLinkType("dcterms:references", "References"),
        )
}

/**
 * Standards (roadmap 9.6): STEP AP242 export of a product structure with its CAD files, and
 * links to resources in requirements, test and change tools (served to them as OSLC, see
 * docs/oslc.md). Errors: `INVALID_EXTERNAL_LINK`, `EXTERNAL_LINK_NOT_FOUND`, plus versioning's.
 */
interface StandardsService {
    /** [rootId]'s structure (latest, or released with [released]), with CAD files unless [withFiles] is false. */
    fun stepExport(
        actor: Actor,
        rootId: UUID,
        released: Boolean = false,
        withFiles: Boolean = true,
    ): StepExport

    fun links(
        actor: Actor,
        objectId: UUID,
    ): List<ExternalLink>

    /** Needs MODIFY on the object. */
    fun addLink(
        actor: Actor,
        objectId: UUID,
        command: AddExternalLink,
    ): ExternalLink

    fun removeLink(
        actor: Actor,
        objectId: UUID,
        linkId: UUID,
    )
}
