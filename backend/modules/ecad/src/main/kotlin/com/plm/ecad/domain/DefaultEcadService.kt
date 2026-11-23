package com.plm.ecad.domain

import com.plm.access.api.ContextId
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.cad.api.AddDependency
import com.plm.cad.api.Associate
import com.plm.cad.api.AssociationKind
import com.plm.cad.api.AssociationService
import com.plm.cad.api.CadService
import com.plm.cad.api.DependencyKind
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.ecad.api.BomCheck
import com.plm.ecad.api.BomDifference
import com.plm.ecad.api.EcadBomLine
import com.plm.ecad.api.EcadImportOptions
import com.plm.ecad.api.EcadImportResult
import com.plm.ecad.api.EcadImported
import com.plm.ecad.api.EcadPreview
import com.plm.ecad.api.EcadService
import com.plm.ecad.api.EcadSheet
import com.plm.ecad.api.ProjectFile
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

internal val ECAD_PROJECT = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000110"))
internal val SCHEMATIC = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000111"))
internal val BOARD = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000112"))

/** A KiCad project read from its files. */
private class Project(
    val name: String,
    val pro: ProjectFile,
    /** Sheets from the root down, each file once. */
    val sheets: List<Sheet>,
    val sheetFiles: Map<String, ProjectFile>,
    val board: ProjectFile?,
    val others: List<ProjectFile>,
    val components: List<Component>,
)

@Service
@Transactional
internal class DefaultEcadService(
    private val versioning: VersioningService,
    private val content: ContentService,
    private val cad: CadService,
    private val associations: AssociationService,
    private val bom: BomService,
    private val types: TypeService,
) : EcadService {
    private fun read(files: List<ProjectFile>): Project {
        val byName = files.associateBy { it.name.substringAfterLast('/').substringAfterLast('\\') }
        val pro =
            byName.values.firstOrNull { it.name.endsWith(".kicad_pro") }
                ?: throw ValidationException(
                    "NOT_A_KICAD_PROJECT",
                    "Choose a KiCad project's files: its .kicad_pro, schematics and board",
                )
        val base = pro.name.substringAfterLast('/').removeSuffix(".kicad_pro")
        val root =
            byName["$base.kicad_sch"] ?: byName.values.firstOrNull { it.name.endsWith(".kicad_sch") }
                ?: throw ValidationException(
                    "NOT_A_KICAD_PROJECT",
                    "The project's schematic ($base.kicad_sch) is missing",
                )
        val sheets = mutableListOf<Sheet>()
        val components = mutableListOf<Component>()
        val seen = mutableSetOf<String>()

        fun walk(
            file: ProjectFile,
            name: String,
        ) {
            val key = file.name.substringAfterLast('/')
            if (!seen.add(key)) return
            val tree =
                try {
                    SExpr.parse(file.bytes.decodeToString())
                } catch (e: Exception) {
                    throw ValidationException("INVALID_KICAD_FILE", "$key isn't a KiCad schematic")
                }
            val sheet = Kicad.sheet(key, name, tree)
            sheets += sheet
            components += Kicad.components(key, tree)
            for ((child, childName) in sheet.children) byName[child]?.let { walk(it, childName) }
        }
        walk(root, base)
        val board = byName["$base.kicad_pcb"] ?: byName.values.firstOrNull { it.name.endsWith(".kicad_pcb") }
        val used = setOf(pro.name) + sheets.map { it.file } + listOfNotNull(board?.name)
        val others =
            byName.values.filter {
                it.name.substringAfterLast('/') !in used &&
                    !it.name.endsWith("-bak") &&
                    !it.name.startsWith("_")
            }
        return Project(
            base,
            pro,
            sheets,
            sheets.associate { it.file to byName.getValue(it.file) },
            board,
            others,
            components,
        )
    }

    private fun isPart(
        actor: Actor,
        o: VersionedObject,
    ) = TypeId.PART in types.lineage(actor, o.typeId)

    /** The part a BOM line is: by its part number, else by the name a new one would get. */
    private fun match(
        actor: Actor,
        line: BomLine,
    ): VersionedObject? {
        fun find(
            text: String,
            test: (VersionedObject) -> Boolean,
        ) = versioning.list(actor, ObjectQuery(text = text.take(100), limit = 50)).firstOrNull {
            test(it) &&
                isPart(actor, it)
        }
        return line.partNumber?.let { pn -> find(pn) { it.number.equals(pn, ignoreCase = true) } }
            ?: find(line.partName) { it.name == line.partName }
    }

    @Transactional(readOnly = true)
    override fun preview(
        actor: Actor,
        files: List<ProjectFile>,
    ): EcadPreview {
        val p = read(files)
        val board = p.board?.let { Kicad.board(SExpr.parse(it.bytes.decodeToString())) }
        return EcadPreview(
            p.name,
            p.sheets.map { s -> EcadSheet(s.file, s.name, p.components.count { it.sheet == s.file }) },
            p.board?.name,
            board?.footprints?.size ?: 0,
            board?.copperLayers ?: 0,
            board?.let { it.maxX - it.minX } ?: 0.0,
            board?.let { it.maxY - it.minY } ?: 0.0,
            Kicad.bom(p.components).map { l ->
                val part = match(actor, l)
                EcadBomLine(
                    l.value,
                    l.footprint,
                    l.partNumber,
                    l.references,
                    part?.id?.value,
                    part?.let { "${it.number} ${it.name}" },
                )
            },
        )
    }

    override fun import(
        actor: Actor,
        files: List<ProjectFile>,
        options: EcadImportOptions,
    ): EcadImportResult {
        val p = read(files)
        val context = ContextId(options.contextId)
        val attributes = mapOf("authoringApplication" to "KiCad", "fileFormat" to "KiCad")

        fun create(
            type: TypeId,
            name: String,
        ) = versioning.create(actor, CreateVersionedObject(type, context, name, attributes = attributes))

        // Documents: the project, a schematic per sheet, the board.
        val project = create(ECAD_PROJECT, p.name)
        val sheets =
            p.sheets.associate {
                it.file to
                    create(SCHEMATIC, if (it.file == p.sheets.first().file) "${p.name} schematic" else it.name)
            }
        val board = p.board?.let { create(BOARD, "${p.name} board") }
        val rootSheet = sheets.getValue(p.sheets.first().file)

        fun fill(
            o: VersionedObject,
            primary: ProjectFile,
            deps: List<AddDependency>,
            attachments: List<Triple<String, String, ByteArray>> = emptyList(),
        ) {
            versioning.checkOut(actor, o.id)
            content.store(
                actor,
                o.id,
                primary.name.substringAfterLast('/'),
                "application/x-kicad",
                primary.bytes,
                ContentRole.PRIMARY,
            )
            attachments.forEach { (name, type, bytes) ->
                content.store(actor, o.id, name, type, bytes, ContentRole.ATTACHMENT)
            }
            deps.forEach { cad.add(actor, o.id.value, it) }
            versioning.checkIn(actor, o.id, "Imported from KiCad")
        }
        fill(
            project,
            p.pro,
            listOfNotNull(
                AddDependency(rootSheet.id.value, DependencyKind.REFERENCE, occurrence = "Schematic"),
                board?.let { AddDependency(it.id.value, DependencyKind.COMPONENT, occurrence = "Board") },
            ),
            p.others.map { Triple(it.name.substringAfterLast('/'), "text/plain", it.bytes) },
        )
        for (s in p.sheets) {
            fill(
                sheets.getValue(s.file),
                p.sheetFiles.getValue(s.file),
                s.children.mapNotNull { (file, name) ->
                    sheets[file]?.let { AddDependency(it.id.value, DependencyKind.REFERENCE, occurrence = name) }
                },
            )
        }
        val pcb = p.board?.let { Kicad.board(SExpr.parse(it.bytes.decodeToString())) }
        if (board != null) {
            val preview = pcb?.let { BoardRenderer.png(it) }
            fill(
                board,
                p.board,
                listOf(
                    AddDependency(
                        rootSheet.id.value,
                        DependencyKind.REFERENCE,
                        required = false,
                        occurrence = "Schematic",
                    ),
                ),
                listOfNotNull(preview?.let { Triple("${p.name} board preview.png", "image/png", it) }),
            )
        }

        // The assembly part (the board as built), owned by the board, with the BOM as its structure.
        val assembly = versioning.create(actor, CreateVersionedObject(TypeId.PART, context, "${p.name} PCBA"))
        versioning.checkOut(actor, assembly.id)
        associations.associate(actor, assembly.id.value, Associate((board ?: project).id.value, AssociationKind.OWNER))
        val created = mutableListOf<EcadImported>()
        val unmatched = mutableListOf<EcadBomLine>()
        var lines = 0
        for (l in Kicad.bom(p.components)) {
            val part =
                match(actor, l)
                    ?: if (options.createParts) {
                        versioning
                            .create(
                                actor,
                                CreateVersionedObject(TypeId.PART, context, l.partName, l.partNumber),
                            ).also {
                                created += EcadImported(it.id.value, it.number, it.name, "PART")
                            }
                    } else {
                        null
                    }
            if (part == null) {
                unmatched += EcadBomLine(l.value, l.footprint, l.partNumber, l.references)
                continue
            }
            bom.add(
                actor,
                assembly.id,
                AddUsage(part.id.value, BigDecimal(l.references.size), designators = l.references.joinToString(", ")),
            )
            lines++
        }
        versioning.checkIn(actor, assembly.id, "Structure from the KiCad schematic")

        fun out(
            o: VersionedObject,
            kind: String,
        ) = EcadImported(o.id.value, o.number, o.name, kind)
        return EcadImportResult(
            out(project, "PROJECT"),
            sheets.values.map { out(it, "SCHEMATIC") } + listOfNotNull(board?.let { out(it, "BOARD") }),
            out(assembly, "PART"),
            lines,
            created,
            unmatched,
        )
    }

    @Transactional(readOnly = true)
    override fun check(
        actor: Actor,
        id: UUID,
    ): BomCheck {
        // The schematic sheets below the project or board, and the part the board (or project) owns.
        // Every kind of dependency (sheets are references), to any depth.
        val docs = linkedSetOf(id)
        val todo = ArrayDeque(listOf(id))
        while (todo.isNotEmpty() && docs.size < 500) {
            cad.dependencies(actor, todo.removeFirst()).filter { it.child.readable }.forEach {
                if (docs.add(it.child.id)) {
                    todo +=
                        it.child.id
                }
            }
        }
        val schematics =
            docs.filter { d -> SCHEMATIC in types.lineage(actor, versioning.get(actor, MasterId(d)).typeId) }
        val components =
            schematics.flatMap { s ->
                val file =
                    content.list(actor, MasterId(s)).firstOrNull { it.role == ContentRole.PRIMARY }
                        ?: return@flatMap emptyList()
                Kicad.components(
                    file.fileName,
                    SExpr.parse(content.open(actor, file.id).use { it.readAllBytes() }.decodeToString()),
                )
            }
        val ecad = Kicad.bom(components)
        val owner =
            docs.firstNotNullOfOrNull { d ->
                associations
                    .associations(
                        actor,
                        d,
                    ).firstOrNull { it.kind == AssociationKind.OWNER && it.part.readable }
                    ?.part
            }
        if (owner == null) return BomCheck(null, ecad.size, emptyList())
        val usages = bom.usages(actor, MasterId(owner.id))
        val differences = mutableListOf<BomDifference>()
        val matched = mutableSetOf<UUID>()
        for (l in ecad) {
            val part = match(actor, l)
            val line = part?.let { pt -> usages.firstOrNull { it.child.id == pt.id.value } }
            if (line == null) {
                differences += BomDifference("MISSING", l.partName, l.references, emptyList())
                continue
            }
            matched += line.id
            val label = "${line.child.number} ${line.child.name}"
            if (line.quantity.compareTo(BigDecimal(l.references.size)) != 0) {
                differences += BomDifference("QUANTITY", label, l.references, line.designators)
            } else if (line.designators.toSet() != l.references.toSet()) {
                differences += BomDifference("DESIGNATORS", label, l.references, line.designators)
            }
        }
        usages.filter { it.id !in matched }.forEach {
            differences += BomDifference("EXTRA", "${it.child.number} ${it.child.name}", emptyList(), it.designators)
        }
        return BomCheck(EcadImported(owner.id, owner.number ?: "", owner.name ?: "", "PART"), ecad.size, differences)
    }
}
