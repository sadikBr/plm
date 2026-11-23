package com.plm.office.api

import com.plm.platform.actor.Actor
import java.util.UUID

/** An Office file property and the attribute it fills (roadmap 8.4). */
data class PropertyMapping(
    /** A core property (title, subject, author, keywords, description, category, status) or a custom one's name. */
    val property: String,
    val attribute: String,
    /** Set on a supertype, not this type itself. */
    val inherited: Boolean = false,
)

data class OfficeFile(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * Office files and the system (roadmap 8.4): the properties of Word, Excel and PowerPoint files
 * fill attributes when a file becomes a document's primary content, and the attributes are written
 * back into the file when it's downloaded; a PDF is made of each for previews when a converter
 * (LibreOffice) is installed. Mappings are per type and inherited by subtypes; site administrators
 * change them. Errors: `ADMIN_REQUIRED`, `TYPE_NOT_FOUND`, `UNKNOWN_ATTRIBUTE`, `INVALID_PROPERTY`,
 * `NOT_AN_OFFICE_FILE`, plus the content module's.
 */
interface OfficeService {
    /** The mappings in effect for [typeId]: its own and its supertypes'. */
    fun mappings(
        actor: Actor,
        typeId: UUID,
    ): List<PropertyMapping>

    /** Replaces [typeId]'s own mappings. */
    fun setMappings(
        actor: Actor,
        typeId: UUID,
        mappings: List<PropertyMapping>,
    ): List<PropertyMapping>

    /** The file with its object's current attributes written into its properties. */
    fun download(
        actor: Actor,
        contentId: UUID,
    ): OfficeFile

    /** The file's properties as they are. */
    fun properties(
        actor: Actor,
        contentId: UUID,
    ): Map<String, String>

    /** Whether a converter for previews is installed. */
    fun previews(): Boolean
}
