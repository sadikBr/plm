package com.plm.retention.domain

import com.plm.access.api.ContextId
import com.plm.bom.api.BomService
import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.DocumentLinkService
import com.plm.bom.api.PastUsage
import com.plm.bom.api.StructureHistoryImportService
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.types.api.TypeId
import com.plm.versioning.api.HistoryImportService
import com.plm.versioning.api.ImportObjectHistory
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.PastIteration
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URLConnection
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** An archived object (`archive.json`, format `plm-archive/1`): everything needed to bring it back. */
internal data class ArchivedObject(
    val format: String = "plm-archive/1",
    val number: String,
    val name: String,
    val typeId: UUID,
    val contextId: UUID,
    val folderId: UUID?,
    val archivedAt: String,
    val iterations: List<ArchivedIteration>,
)

internal data class ArchivedIteration(
    val revision: String,
    val iteration: Int,
    val state: String,
    val createdAt: String,
    val createdBy: String,
    val comment: String?,
    val attributes: Map<String, Any?>,
    val files: List<ArchivedFile>,
    /** Children by number: they stay in the system. */
    val uses: List<ArchivedUse>,
    val describedBy: List<String>,
)

internal data class ArchivedFile(
    val path: String,
    val name: String,
    val role: String,
    val contentType: String,
)

internal data class ArchivedUse(
    val child: String,
    val quantity: BigDecimal,
    val unit: String,
    val findNumber: String?,
)

/** Writes an object with its whole history to a self-contained zip, and brings it back (roadmap 9.8). */
@Component
internal class Archiver(
    private val versioning: VersioningService,
    private val content: ContentService,
    private val bom: BomService,
    private val links: DocumentLinkService,
    private val history: HistoryImportService,
    private val structure: StructureHistoryImportService,
) {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    class Written(
        val bytes: ByteArray,
        val iterations: Int,
        val files: Int,
    )

    fun write(
        actor: Actor,
        obj: VersionedObject,
        now: Instant,
    ): Written {
        val out = ByteArrayOutputStream()
        var fileCount = 0
        // Oldest first, in the order they were made (history comes newest first).
        val iterations = versioning.history(actor, obj.id).reversed()
        ZipOutputStream(out).use { zip ->
            val archived =
                iterations.map { i ->
                    val files =
                        content.list(actor, obj.id, i.id.value).map { item ->
                            val path = "files/${i.version}/${item.fileName}"
                            zip.putNextEntry(ZipEntry(path))
                            content.open(actor, item.id).use { it.copyTo(zip) }
                            zip.closeEntry()
                            fileCount++
                            ArchivedFile(path, item.fileName, item.role.name, item.contentType)
                        }
                    val uses =
                        runCatching { bom.usages(actor, obj.id, i.id.value) }
                            .getOrDefault(emptyList())
                            .mapNotNull { u ->
                                u.child.number?.let { ArchivedUse(it, u.quantity, u.unit, u.findNumber) }
                            }
                    val described =
                        runCatching { links.links(actor, obj.id, i.id.value) }
                            .getOrDefault(emptyList())
                            .filter { it.kind == DocumentLinkKind.DESCRIBED_BY }
                            .mapNotNull { it.document.number }
                    ArchivedIteration(
                        i.revision,
                        i.iteration,
                        i.state,
                        i.createdAt.toString(),
                        i.createdBy,
                        i.comment,
                        i.attributes,
                        files,
                        uses,
                        described,
                    )
                }
            zip.putNextEntry(ZipEntry("archive.json"))
            zip.write(
                json.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                    ArchivedObject(
                        number = obj.number,
                        name = obj.name,
                        typeId = obj.typeId.value,
                        contextId = obj.contextId.value,
                        folderId = obj.folderId,
                        archivedAt = now.toString(),
                        iterations = archived,
                    ),
                ),
            )
            zip.closeEntry()
        }
        return Written(out.toByteArray(), iterations.size, fileCount)
    }

    private fun find(
        actor: Actor,
        number: String,
    ) = versioning.list(actor, ObjectQuery(text = number, limit = 20)).firstOrNull { it.number.equals(number, true) }

    /** Brings the object back with its history; returns its new id. */
    fun restore(
        actor: Actor,
        bytes: ByteArray,
    ): UUID {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            generateSequence { z.nextEntry }.forEach {
                entries[it.name] =
                    z.readAllBytes()
            }
        }
        val a: ArchivedObject = json.readValue(entries.getValue("archive.json"))
        if (find(actor, a.number) !=
            null
        ) {
            throw ConflictException(
                "DUPLICATE_NUMBER",
                "An object numbered ${a.number} is here again: it can't be restored",
            )
        }
        val imported =
            history.importObject(
                actor,
                ImportObjectHistory(
                    TypeId(a.typeId),
                    ContextId(a.contextId),
                    a.number,
                    a.name,
                    a.folderId,
                    a.iterations.map {
                        PastIteration(
                            it.revision,
                            it.iteration,
                            it.state,
                            it.attributes,
                            it.comment,
                            Instant.parse(it.createdAt),
                            it.createdBy,
                        )
                    },
                ),
            )
        a.iterations.forEachIndexed { n, i ->
            val iterationId = imported.iterationIds[n].value
            for (f in i.files) {
                content.importFile(
                    actor,
                    imported.id,
                    iterationId,
                    f.name,
                    f.contentType.ifBlank {
                        URLConnection.guessContentTypeFromName(
                            f.name,
                        ) ?: "application/octet-stream"
                    },
                    entries.getValue(f.path),
                    ContentRole.valueOf(f.role),
                )
            }
            val lines =
                i.uses.mapNotNull { u ->
                    find(actor, u.child)?.let { PastUsage(it.id.value, u.quantity, u.unit, u.findNumber) }
                }
            if (lines.isNotEmpty()) structure.importUsages(actor, imported.id, iterationId, lines)
            i.describedBy.forEach { d ->
                find(actor, d)?.let {
                    structure.importDocumentLink(
                        actor,
                        imported.id,
                        iterationId,
                        MasterId(it.id.value),
                        DocumentLinkKind.DESCRIBED_BY,
                    )
                }
            }
        }
        return imported.id.value
    }
}
