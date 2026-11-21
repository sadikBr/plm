package com.plm.cad.domain

import com.plm.access.api.ContextId
import com.plm.cad.api.AddDependency
import com.plm.cad.api.Associate
import com.plm.cad.api.CadKind
import com.plm.cad.api.ImportedObject
import com.plm.cad.api.StepImportOptions
import com.plm.cad.api.StepImportResult
import com.plm.cad.api.StepImportService
import com.plm.cad.api.StepLine
import com.plm.cad.api.StepPreview
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private const val MAX_STEP_BYTES = 50 * 1024 * 1024
private const val MAX_PRODUCTS = 2_000

@Service
@Transactional
internal class DefaultStepImportService(
    private val cad: DefaultCadService,
    private val associations: DefaultAssociationService,
    private val versioning: VersioningService,
    private val content: ContentService,
) : StepImportService {
    private fun read(
        fileName: String,
        bytes: ByteArray,
    ): StepStructure {
        if (bytes.size >
            MAX_STEP_BYTES
        ) {
            throw ValidationException("STEP_TOO_LARGE", "STEP files are imported up to 50 MB")
        }
        if (!fileName.lowercase().let { it.endsWith(".stp") || it.endsWith(".step") || it.endsWith(".p21") }) {
            throw ValidationException("INVALID_STEP", "Choose a .stp or .step file")
        }
        val s = StepStructure.read(String(bytes, Charsets.ISO_8859_1))
        if (s.products.isEmpty()) throw ValidationException("NO_PRODUCT", "The file holds no product")
        if (s.products.size >
            MAX_PRODUCTS
        ) {
            throw ValidationException("STEP_TOO_LARGE", "At most $MAX_PRODUCTS models at once")
        }
        return s
    }

    private fun format(schema: String): String =
        when {
            "242" in schema || schema.uppercase().contains("AP242") -> "STEP AP242"
            "214" in schema || "AUTOMOTIVE" in schema.uppercase() -> "STEP AP214"
            "203" in schema || "CONFIG_CONTROL" in schema.uppercase() -> "STEP AP203"
            else -> "STEP"
        }

    @Transactional(readOnly = true)
    override fun preview(
        actor: Actor,
        fileName: String,
        bytes: ByteArray,
    ): StepPreview {
        val s = read(fileName, bytes)
        val lines = mutableListOf<StepLine>()

        fun kind(k: Int) = if (s.children(k).isEmpty()) CadKind.PART else CadKind.ASSEMBLY

        fun walk(
            k: Int,
            depth: Int,
            occurrence: String?,
            position: List<Double>?,
            path: Set<Int>,
        ) {
            if (lines.size > 5_000) return
            val p = s.products.getValue(k)
            lines += StepLine(depth, p.name, p.partNumber, kind(k), occurrence, position)
            if (k in path) return
            s.children(k).forEach { walk(it.child, depth + 1, it.occurrence, it.transform.subList(12, 15), path + k) }
        }
        s.roots.forEach { walk(it, 0, null, null, emptySet()) }
        return StepPreview(
            fileName,
            format(s.schema),
            s.unitToMm,
            s.products.size,
            s.products.keys.count { kind(it) == CadKind.ASSEMBLY },
            lines,
        )
    }

    override fun import(
        actor: Actor,
        fileName: String,
        bytes: ByteArray,
        options: StepImportOptions,
    ): StepImportResult {
        val s = read(fileName, bytes)
        val context = ContextId(options.contextId)
        val attributes = mapOf("authoringApplication" to "STEP import", "fileFormat" to format(s.schema))
        val root = s.roots.first()
        val order = s.bottomUp()
        // A CAD document per product, components first.
        val ids = HashMap<Int, MasterId>()
        val models = mutableListOf<ImportedObject>()
        for (k in order) {
            val p = s.products.getValue(k)
            val assembly = s.children(k).isNotEmpty()
            val type = if (assembly) TypeId.CAD_ASSEMBLY else TypeId.CAD_PART
            val o = versioning.create(actor, CreateVersionedObject(type, context, p.name, attributes = attributes))
            ids[k] = o.id
            models += ImportedObject(o.id.value, o.number, o.name, if (assembly) "ASSEMBLY" else "PART")
        }
        // Assemblies get their components; the top model the file.
        for (k in order) {
            val usages = s.children(k)
            if (usages.isEmpty() && k != root) continue
            val id = ids.getValue(k)
            versioning.checkOut(actor, id)
            usages.forEach {
                cad.add(
                    actor,
                    id.value,
                    AddDependency(ids.getValue(it.child).value, occurrence = it.occurrence, transform = it.transform),
                )
            }
            if (k ==
                root
            ) {
                content.store(actor, id, fileName, "model/step", bytes, ContentRole.PRIMARY, "Imported STEP file")
            }
            versioning.checkIn(actor, id, "Imported from $fileName")
        }
        val parts = mutableListOf<ImportedObject>()
        if (options.createParts) {
            val partIds = HashMap<Int, MasterId>()
            for (k in order) {
                val p = s.products.getValue(k)
                val part = versioning.create(actor, CreateVersionedObject(TypeId.PART, context, p.name))
                versioning.checkOut(actor, part.id)
                associations.associate(actor, part.id.value, Associate(ids.getValue(k).value))
                versioning.checkIn(actor, part.id, "Owned by its imported model")
                partIds[k] = part.id
                parts += ImportedObject(part.id.value, part.number, part.name, "PART")
            }
            if (options.build) {
                for (k in order.filter { s.children(it).isNotEmpty() }) {
                    val part = partIds.getValue(k)
                    versioning.checkOut(actor, part)
                    associations.build(actor, part.value)
                    versioning.checkIn(actor, part, "Built from its imported model")
                }
            }
        }
        return StepImportResult(ids.getValue(root).value, models.reversed(), parts.reversed())
    }
}
