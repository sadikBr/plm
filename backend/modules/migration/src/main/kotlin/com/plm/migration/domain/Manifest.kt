package com.plm.migration.domain

import com.plm.platform.error.ValidationException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream

/** The package's `manifest.json` (format `plm-migration/1`). */
internal data class Manifest(
    val format: String,
    val source: String? = null,
    val users: List<LegacyUser> = emptyList(),
    val objects: List<LegacyObject> = emptyList(),
)

internal data class LegacyUser(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val email: String? = null,
)

internal data class LegacyObject(
    /** The other system's id; links refer to objects by it. */
    val id: String,
    val type: String,
    val number: String,
    val name: String,
    val folder: String? = null,
    /** Oldest first. */
    val iterations: List<LegacyIteration> = emptyList(),
)

internal data class LegacyIteration(
    val version: String,
    val iteration: Int,
    val state: String,
    /** ISO date-time. */
    val createdAt: String,
    val createdBy: String? = null,
    val comment: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val files: List<LegacyFile> = emptyList(),
    /** Its structure: child objects by legacy id. */
    val uses: List<LegacyUse> = emptyList(),
    /** Documents (legacy ids) that describe it. */
    val describedBy: List<String> = emptyList(),
)

internal data class LegacyFile(
    /** Path inside the zip. */
    val path: String,
    /** PRIMARY or ATTACHMENT. */
    val role: String = "PRIMARY",
)

internal data class LegacyUse(
    val child: String,
    val quantity: BigDecimal = BigDecimal.ONE,
    val unit: String? = null,
    val findNumber: String? = null,
)

/** The manifest and the names of the files in a package. */
internal class PackageContents(
    val manifest: Manifest,
    val files: Set<String>,
)

internal object Packages {
    const val FORMAT = "plm-migration/1"

    private val json =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    private fun bad(message: String): Nothing = throw ValidationException("INVALID_MIGRATION_PACKAGE", message)

    fun read(bytes: ByteArray): PackageContents {
        var manifest: Manifest? = null
        val files = mutableSetOf<String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    if (e.isDirectory) return@forEach
                    val name = e.name.replace('\\', '/').trimStart('/')
                    if (name == "manifest.json") {
                        manifest =
                            try {
                                json.readValue<Manifest>(zip.readAllBytes())
                            } catch (ex: tools.jackson.core.JacksonException) {
                                bad("manifest.json can't be read: ${ex.originalMessage}")
                            }
                    } else {
                        files += name
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            bad("The package must be a .zip")
        }
        val m = manifest ?: bad("The zip has no manifest.json at its top")
        if (m.format != FORMAT) bad("The manifest's format is '${m.format}': expected $FORMAT")
        return PackageContents(m, files)
    }

    /** The bytes of the files named, read in one pass. */
    fun files(
        bytes: ByteArray,
        wanted: Set<String>,
    ): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                val name = e.name.replace('\\', '/').trimStart('/')
                if (name in wanted) out[name] = zip.readAllBytes()
            }
        }
        return out
    }

    fun mappings(text: String?): com.plm.migration.api.MigrationMappings =
        if (text.isNullOrBlank()) {
            com.plm.migration.api
                .MigrationMappings()
        } else {
            json.readValue(text)
        }

    fun write(value: Any): String = json.writeValueAsString(value)
}
