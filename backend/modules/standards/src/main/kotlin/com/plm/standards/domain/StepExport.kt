package com.plm.standards.domain

import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.DocumentLinkService
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.standards.api.StepExport
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Part 21 lines, numbered as they are added. */
private class P21Writer {
    private val lines = mutableListOf<String>()

    fun add(entity: String): Int {
        lines += entity
        return lines.size
    }

    fun data() = lines.mapIndexed { i, l -> "#${i + 1}=$l;" }.joinToString("\n")
}

/** A STEP string: apostrophes doubled, non-ASCII as \X2\ hex \X0\ (ISO 10303-21). */
internal fun p21(text: String?): String {
    if (text == null) return "$"
    val sb = StringBuilder("'")
    for (ch in text) {
        when {
            ch == '\'' -> sb.append("''")
            ch == '\\' -> sb.append("\\\\")
            ch.code in 32..126 -> sb.append(ch)
            else -> sb.append("\\X2\\").append("%04X".format(ch.code)).append("\\X0\\")
        }
    }
    return sb.append("'").toString()
}

/**
 * Writes a product structure as STEP AP242 (roadmap 9.6): each part a PRODUCT with its version
 * (PRODUCT_DEFINITION_FORMATION) and design definition; each unit of quantity a
 * NEXT_ASSEMBLY_USAGE_OCCURRENCE; the CAD files of the documents describing a part as AP242
 * external document references (DOCUMENT_FILE + APPLIED_DOCUMENT_REFERENCE), shipped in the zip.
 */
@Component
internal class StepExporter(
    private val versioning: VersioningService,
    private val bom: BomService,
    private val links: DocumentLinkService,
    private val content: ContentService,
    private val clock: Clock,
) {
    private val cadExtensions =
        setOf(
            "stp",
            "step",
            "stpz",
            "jt",
            "fcstd",
            "sldprt",
            "sldasm",
            "prt",
            "asm",
            "catpart",
            "catproduct",
            "ipt",
            "iam",
            "x_t",
            "igs",
            "iges",
            "stl",
            "3mf",
        )

    fun export(
        actor: Actor,
        rootId: UUID,
        released: Boolean,
        withFiles: Boolean,
    ): StepExport {
        val root = versioning.get(actor, MasterId(rootId))
        val spec = if (released) ConfigSpec.RELEASED else ConfigSpec.LATEST
        val structure = bom.structure(actor, root.id, spec, depth = 50)
        val w = P21Writer()
        val appContext = w.add("APPLICATION_CONTEXT('managed model based 3d engineering')")
        w.add(
            "APPLICATION_PROTOCOL_DEFINITION('international standard','ap242_managed_model_based_3d_engineering',2020,#$appContext)",
        )
        val productContext = w.add("PRODUCT_CONTEXT('',#$appContext,'mechanical')")
        val pdContext = w.add("PRODUCT_DEFINITION_CONTEXT('part definition',#$appContext,'design')")
        val docType = w.add("DOCUMENT_TYPE('cad')")
        val definitions = mutableMapOf<UUID, Int>()
        val files = linkedMapOf<String, UUID>()

        fun product(
            id: UUID,
            number: String,
            name: String,
            version: String,
            iterationId: UUID?,
        ): Int {
            definitions[id]?.let { return it }
            val p = w.add("PRODUCT(${p21(number)},${p21(name)},'',(#$productContext))")
            w.add("PRODUCT_RELATED_PRODUCT_CATEGORY('part',$,(#$p))")
            val formation = w.add("PRODUCT_DEFINITION_FORMATION(${p21(version)},'',#$p)")
            val pd = w.add("PRODUCT_DEFINITION('design','',#$formation,#$pdContext)")
            definitions[id] = pd
            if (withFiles && iterationId != null) {
                for (link in links.links(actor, MasterId(id), iterationId)) {
                    if (link.kind != DocumentLinkKind.DESCRIBED_BY || !link.document.readable) continue
                    val docIteration = link.document.iterationId ?: continue
                    for (item in content.list(actor, MasterId(link.document.id), docIteration)) {
                        if (item.role != ContentRole.PRIMARY) continue
                        if (item.fileName.substringAfterLast('.', "").lowercase() !in cadExtensions) continue
                        val path = "cad/${link.document.number}/${item.fileName}"
                        files.putIfAbsent(path, item.id)
                        val doc =
                            w.add(
                                "DOCUMENT_FILE(${p21(path)},${p21(item.fileName)},$,#$docType,'',$)",
                            )
                        w.add("APPLIED_DOCUMENT_REFERENCE(#$doc,${p21(link.document.number)},(#$pd))")
                    }
                }
            }
            return pd
        }

        val rootPd = product(root.id.value, root.number, root.name, root.latest.version, structure.rootIterationId)
        val byPath = mutableMapOf("" to rootPd)
        var occurrences = 0
        for (node in structure.nodes) {
            val child = node.line.child
            if (!child.readable || child.number == null) continue
            val parentPd = byPath[node.parentPath ?: ""] ?: continue
            val childPd =
                product(child.id, child.number!!, child.name ?: child.number!!, child.version ?: "", child.iterationId)
            byPath[node.path] = childPd
            val q = node.line.quantity
            // Whole quantities become one occurrence each, as CAD systems read them; others one occurrence.
            val count = if (q.stripTrailingZeros().scale() <= 0 && q.toInt() in 1..500) q.toInt() else 1
            repeat(count) { n ->
                val id = if (count == 1) node.path else "${node.path}.${n + 1}"
                w.add(
                    "NEXT_ASSEMBLY_USAGE_OCCURRENCE(${p21(
                        id,
                    )},${p21(node.line.findNumber ?: "")},'',#$parentPd,#$childPd,$)",
                )
                occurrences++
            }
        }
        val stamp = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(clock.instant().atOffset(ZoneOffset.UTC).withNano(0))
        val name = "${root.number}.stp"
        val text =
            """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Product structure of ${root.number}'),'2;1');
            FILE_NAME(${p21(name)},'$stamp',(''),(''),'PLM','PLM','');
            FILE_SCHEMA(('AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF { 1 0 10303 442 1 1 4 }'));
            ENDSEC;
            DATA;
            ${"$"}DATA
            ENDSEC;
            END-ISO-10303-21;
            """.trimIndent().replace("\$DATA", w.data()) + "\n"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
            for ((path, contentId) in files) {
                zip.putNextEntry(ZipEntry(path))
                content.open(actor, contentId).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return StepExport(
            "${root.number}-step.zip",
            out.toByteArray(),
            definitions.size,
            occurrences,
            files.size,
        )
    }
}
