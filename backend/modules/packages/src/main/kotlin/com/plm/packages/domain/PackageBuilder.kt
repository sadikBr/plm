package com.plm.packages.domain

import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.DocumentLinkService
import com.plm.content.api.ContentService
import com.plm.identity.api.PrincipalDirectory
import com.plm.packages.api.PackageContents
import com.plm.packages.api.PackageMember
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeService
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An object in a package, with what's needed to write it. */
internal class Resolved(
    val obj: VersionedObject,
    val iteration: Iteration,
    val baseClass: BaseClass,
    val typeName: String,
    val reason: String,
)

/** Works out a package's members and writes its zip (roadmap 9.4). */
@Component
internal class PackageBuilder(
    private val versioning: VersioningService,
    private val bom: BomService,
    private val links: DocumentLinkService,
    private val content: ContentService,
    private val types: TypeService,
    private val principals: PrincipalDirectory,
) {
    private val json = JsonMapper.builder().build()

    private fun specOf(spec: String) = if (spec == "RELEASED") ConfigSpec.RELEASED else ConfigSpec.LATEST

    /** The iteration [spec] picks of [id], if any. */
    private fun pick(
        actor: Actor,
        id: UUID,
        spec: String,
    ): Pair<VersionedObject, Iteration>? {
        val obj = runCatching { versioning.get(actor, MasterId(id)) }.getOrNull() ?: return null
        if (spec != "RELEASED") return obj to obj.latest
        val released = versioning.history(actor, obj.id).firstOrNull { it.state == "RELEASED" } ?: return null
        return obj to released
    }

    private fun at(
        actor: Actor,
        id: UUID,
        iterationId: UUID,
    ): Pair<VersionedObject, Iteration>? {
        val obj = runCatching { versioning.get(actor, MasterId(id)) }.getOrNull() ?: return null
        val it =
            if (obj.latest.id.value ==
                iterationId
            ) {
                obj.latest
            } else {
                versioning.history(actor, obj.id).firstOrNull { it.id.value == iterationId }
            }
        return it?.let { obj to it }
    }

    fun resolve(
        actor: Actor,
        rootIds: List<UUID>,
        spec: String,
        contents: PackageContents,
    ): List<Resolved> {
        val out = linkedMapOf<UUID, Resolved>()
        val typeCache = mutableMapOf<UUID, Pair<BaseClass, String>>()

        fun add(
            pair: Pair<VersionedObject, Iteration>?,
            reason: String,
        ) {
            val (obj, iteration) = pair ?: return
            if (obj.id.value in out) return
            val (base, name) =
                typeCache.getOrPut(obj.typeId.value) {
                    types.get(actor, obj.typeId).let {
                        it.baseClass to
                            it.name
                    }
                }
            out[obj.id.value] = Resolved(obj, iteration, base, name, reason)
        }
        rootIds.forEach { add(pick(actor, it, spec), "ROOT") }
        if (contents.structure) {
            for (root in out.values.filter { it.baseClass == BaseClass.PART }.toList()) {
                val result =
                    try {
                        bom.structure(actor, root.obj.id, specOf(spec), depth = 50)
                    } catch (e: PlmException) {
                        continue
                    }
                for (node in result.nodes) {
                    val child = node.line.child
                    val iteration = child.iterationId ?: continue
                    if (!child.readable) continue
                    add(at(actor, child.id, iteration), "STRUCTURE")
                }
            }
        }
        if (contents.documents) {
            for (part in out.values.filter { it.baseClass == BaseClass.PART }.toList()) {
                for (link in links.links(actor, part.obj.id, part.iteration.id.value)) {
                    val doc = link.document
                    if (!doc.readable) continue
                    val iteration = doc.iterationId
                    add(if (iteration != null) at(actor, doc.id, iteration) else pick(actor, doc.id, spec), "DOCUMENT")
                }
            }
        }
        return out.values.toList()
    }

    fun members(
        actor: Actor,
        resolved: List<Resolved>,
    ): List<PackageMember> =
        resolved.map {
            PackageMember(
                it.obj.id.value,
                it.obj.number,
                it.obj.name,
                it.typeName,
                it.iteration.version,
                it.iteration.state,
                it.iteration.id.value,
                it.reason,
                if (it.baseClass == BaseClass.PART) 0 else content.list(actor, it.obj.id, it.iteration.id.value).size,
            )
        }

    /** The package as a migration-format zip (docs/migration-format.md). */
    fun zip(
        actor: Actor,
        resolved: List<Resolved>,
        contents: PackageContents,
        source: String,
    ): ByteArray {
        val ids = resolved.map { it.obj.id.value }.toSet()
        val out = ByteArrayOutputStream()
        val authors = mutableSetOf<String>()
        ZipOutputStream(out).use { zip ->
            val objects =
                resolved.map { r ->
                    val i = r.iteration
                    authors += i.createdBy
                    val files = mutableListOf<Map<String, Any?>>()
                    if (contents.files || contents.pdfs) {
                        for (item in content.list(actor, r.obj.id, i.id.value)) {
                            val folder = "files/${safe(r.obj.number)}/${i.version}"
                            if (contents.files) {
                                val path = "$folder/${safe(item.fileName)}"
                                zip.putNextEntry(ZipEntry(path))
                                content.open(actor, item.id).use { it.copyTo(zip) }
                                zip.closeEntry()
                                files += mapOf("path" to path, "role" to item.role.name)
                            }
                            if (contents.pdfs && !item.fileName.lowercase().endsWith(".pdf")) {
                                val pdf = content.openDerived(actor, item.id, "pdf") ?: continue
                                val path = "$folder/${safe(item.fileName.substringBeforeLast('.'))}.pdf"
                                zip.putNextEntry(ZipEntry(path))
                                pdf.use { it.copyTo(zip) }
                                zip.closeEntry()
                                files += mapOf("path" to path, "role" to "ATTACHMENT")
                            }
                        }
                    }
                    val uses =
                        if (r.baseClass == BaseClass.PART && contents.structure) {
                            bom.usages(actor, r.obj.id, i.id.value).filter { it.child.id in ids }.map {
                                mapOf(
                                    "child" to it.child.id.toString(),
                                    "quantity" to it.quantity,
                                    "unit" to it.unit,
                                    "findNumber" to it.findNumber,
                                )
                            }
                        } else {
                            emptyList()
                        }
                    val describedBy =
                        if (r.baseClass == BaseClass.PART && contents.documents) {
                            links
                                .links(actor, r.obj.id, i.id.value)
                                .filter { it.kind == DocumentLinkKind.DESCRIBED_BY && it.document.id in ids }
                                .map { it.document.id.toString() }
                        } else {
                            emptyList()
                        }
                    mapOf(
                        "id" to
                            r.obj.id.value
                                .toString(),
                        "type" to r.typeName,
                        "number" to r.obj.number,
                        "name" to r.obj.name,
                        "iterations" to
                            listOf(
                                mapOf(
                                    "version" to i.revision,
                                    "iteration" to i.iteration,
                                    "state" to i.state,
                                    "createdAt" to i.createdAt.toString(),
                                    "createdBy" to i.createdBy,
                                    "comment" to i.comment,
                                    "attributes" to i.attributes,
                                    "files" to files,
                                    "uses" to uses,
                                    "describedBy" to describedBy,
                                ),
                            ),
                    )
                }
            val users =
                authors.map { id ->
                    val name =
                        principals.displayName(
                            actor.tenant,
                            com.plm.platform.id
                                .PrincipalId(id),
                        )
                    val username =
                        runCatching {
                            principals
                                .get(
                                    actor,
                                    com.plm.platform.id
                                        .PrincipalId(id),
                                ).username
                        }.getOrNull()
                            ?: id
                    mapOf("id" to id, "username" to username, "displayName" to name)
                }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                json.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                    mapOf("format" to "plm-migration/1", "source" to source, "users" to users, "objects" to objects),
                ),
            )
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
