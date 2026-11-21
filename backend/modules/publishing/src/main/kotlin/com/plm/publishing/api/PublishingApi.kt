package com.plm.publishing.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

enum class PublishTrigger { CHECK_IN, STATE }

data class PublishRule(
    val id: UUID,
    val name: String,
    /** Type names (subtypes included); empty: every type. */
    val types: List<String>,
    val trigger: PublishTrigger,
    /** For STATE: the state that publishes. */
    val state: String?,
    /** Stamp the pages with number, version and state. */
    val watermark: Boolean,
    val active: Boolean,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class PublishRuleSettings(
    val name: String,
    val trigger: PublishTrigger,
    val types: List<String> = emptyList(),
    val state: String? = null,
    val watermark: Boolean = true,
    val active: Boolean = true,
)

data class PublishSettings(
    /** PDFs downloaded through the platform carry who downloaded them, and when. */
    val watermarkDownloads: Boolean,
)

data class Publication(
    val id: UUID,
    val objectId: UUID,
    val version: String,
    val state: String,
    /** The rule that made it; null when published by hand. */
    val rule: String?,
    /** QUEUED, READY or FAILED. */
    val status: String,
    /** FILE (its PDF, stamped) or DATASHEET (generated). */
    val source: String?,
    val fileName: String?,
    val pages: Int?,
    val error: String?,
    val createdAt: Instant,
    val createdBy: String,
)

/** A PDF to hand to someone: the bytes and their name. */
class PdfFile(
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * Publishing (roadmap 6.7). Rules say which types publish a PDF, on check-in or on reaching a
 * state: the version's PDF file, stamped with its number, version and state, or a data sheet made
 * from the object when it has none. Every PDF handed out (publications, data sheets, PDF files)
 * can carry who downloaded it and when. Rules and settings are for site administrators
 * (`ADMIN_REQUIRED`); errors: `RULE_NOT_FOUND`, `INVALID_RULE`, `PUBLICATION_NOT_FOUND`, `NOT_A_PDF`.
 */
interface PublishingService {
    fun rules(actor: Actor): List<PublishRule>

    fun saveRule(
        actor: Actor,
        id: UUID?,
        settings: PublishRuleSettings,
    ): PublishRule

    fun deleteRule(
        actor: Actor,
        id: UUID,
    )

    fun settings(actor: Actor): PublishSettings

    fun saveSettings(
        actor: Actor,
        settings: PublishSettings,
    ): PublishSettings

    /** The object's publications, newest first. */
    fun publications(
        actor: Actor,
        objectId: UUID,
    ): List<Publication>

    /** Publishes the latest version now. */
    fun publish(
        actor: Actor,
        objectId: UUID,
    ): Publication

    fun download(
        actor: Actor,
        publicationId: UUID,
    ): PdfFile

    /** A data sheet of the object's latest version (or [iterationId]), made now. */
    fun datasheet(
        actor: Actor,
        objectId: UUID,
    ): PdfFile

    /** A stored PDF file, stamped for the person downloading it. */
    fun watermarked(
        actor: Actor,
        contentId: UUID,
    ): PdfFile
}
