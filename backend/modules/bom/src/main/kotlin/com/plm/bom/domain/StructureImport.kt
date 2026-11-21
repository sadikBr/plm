package com.plm.bom.domain

import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.ImportAction
import com.plm.bom.api.ImportPreview
import com.plm.bom.api.ImportRow
import com.plm.bom.api.PreviewRow
import com.plm.bom.api.StructureImportService
import com.plm.bom.api.UpdateUsage
import com.plm.jobs.api.Job
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import com.plm.jobs.api.JobService
import com.plm.jobs.api.SubmitJob
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.types.api.BaseClass
import com.plm.types.api.EnumerationService
import com.plm.types.api.TypeId
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.util.UUID

/** A part the file names: an existing one by id, or one to create, by number or name. */
private fun key(
    action: ImportAction?,
    partId: UUID?,
    row: ImportRow,
): String? =
    when (action) {
        ImportAction.MATCH -> "id:$partId"
        ImportAction.CREATE -> "new:" + (row.number?.lowercase() ?: ("name:" + row.name?.lowercase()))
        null -> null
    }

/** What the import does, worked out from the rows: shared by the preview and the job. */
internal data class ImportPlan(
    val preview: ImportPreview,
    /** Parent row index (null: the root) of each row. */
    val parents: List<Int?>,
    val keys: List<String?>,
    val contextId: UUID,
)

@Service
internal class DefaultStructureImportService(
    private val versioning: VersioningService,
    private val types: TypeService,
    private val typeRules: TypeRuleService,
    private val enumerations: EnumerationService,
    private val jobs: JobService,
) : StructureImportService {
    override fun read(
        fileName: String,
        bytes: ByteArray,
    ): List<ImportRow> = Sheets.read(fileName, bytes)

    override fun preview(
        actor: Actor,
        rootId: MasterId,
        rows: List<ImportRow>,
    ): ImportPreview = plan(actor, rootId, rows).preview

    override fun start(
        actor: Actor,
        rootId: MasterId,
        rows: List<ImportRow>,
        fileName: String?,
    ): Job {
        val preview = preview(actor, rootId, rows)
        if (preview.errors > 0) {
            throw ValidationException(
                "INVALID_IMPORT",
                "Fix the ${preview.errors} line${if (preview.errors == 1) "" else "s"} with errors first",
            )
        }
        return jobs.submit(
            actor,
            SubmitJob(
                StructureImportJob.TYPE,
                StructureImportJob.payload(
                    StructureImportJob.Request(rootId.value, preview.rootNumber, fileName, rows),
                ),
            ),
        )
    }

    fun manualNumbers(actor: Actor) = typeRules.rules(actor, TypeId.PART).allowManualNumber

    fun plan(
        actor: Actor,
        rootId: MasterId,
        given: List<ImportRow>,
    ): ImportPlan {
        val root = versioning.get(actor, rootId)
        requirePart(actor, root)
        val unitNames = unitNames(actor)
        val units = unitNames.values.toSet()
        val manual = manualNumbers(actor)
        val found = mutableMapOf<String, VersionedObject?>()

        fun lookUp(number: String) =
            found.getOrPut(number.lowercase()) {
                versioning
                    .list(actor, ObjectQuery(text = number, limit = 50))
                    .firstOrNull { it.number.equals(number, ignoreCase = true) }
            }

        // A level-0 first row stands for the root itself. Units may be given by key or by label (as exported).
        val rows =
            given
                .filterIndexed { i, r ->
                    !(i == 0 && r.level == 0 && (r.number == null || r.number.equals(root.number, ignoreCase = true)))
                }.map { r -> r.copy(unit = r.unit?.let { unitNames[it.lowercase()] ?: it }) }
        val parents = mutableListOf<Int?>()
        val errors = rows.map { mutableListOf<String>() }
        val warnings = rows.map { mutableListOf<String>() }
        val actions = mutableListOf<ImportAction?>()
        val parts = mutableListOf<VersionedObject?>()
        var previousLevel = 0
        rows.forEachIndexed { i, r ->
            val e = errors[i]
            val w = warnings[i]
            when {
                r.level < 1 -> e += "Level must be a whole number from 1 (level 1 is used by ${root.number})"
                r.level > previousLevel + 1 -> e += "Level jumps from $previousLevel to ${r.level}"
            }
            val level = r.level.coerceIn(1, previousLevel + 1)
            parents +=
                (i - 1 downTo 0).firstOrNull { j -> rows[j].level.coerceAtLeast(1) == level - 1 }?.takeIf { level > 1 }
            previousLevel = level
            val quantity = r.quantity ?: BigDecimal.ONE
            if (quantity.signum() <= 0) e += "The quantity must be a number above 0"
            if (r.unit != null &&
                r.unit !in units
            ) {
                e += "${r.unit} isn't a unit of use (${units.sorted().joinToString(", ")})"
            }
            if ((r.findNumber?.length ?: 0) > 40) e += "A find number is at most 40 characters"
            try {
                val list = Designators.parse(r.designators)
                if (list.isNotEmpty()) {
                    val whole = (r.unit ?: "each") == "each" && quantity.stripTrailingZeros().scale() <= 0
                    if (!whole) {
                        e += "Reference designators need a whole quantity of single pieces (unit: each)"
                    } else if (BigDecimal(list.size).compareTo(quantity) != 0) {
                        e +=
                            "${list.size} reference designators for a quantity of ${quantity.stripTrailingZeros().toPlainString()}"
                    }
                }
            } catch (ex: ValidationException) {
                e += ex.message ?: "Invalid reference designators"
            }
            val part = r.number?.let(::lookUp)
            if (part != null && !isPart(actor, part)) {
                e += "${part.number} isn't a part"
                actions += null
                parts += null
                return@forEachIndexed
            }
            when {
                part != null -> {
                    actions += ImportAction.MATCH
                    if (r.name != null && !r.name.equals(part.name, ignoreCase = true)) {
                        w += "Named “${part.name}” in the system: the import doesn't rename it"
                    }
                }
                r.name.isNullOrBlank() -> {
                    e +=
                        if (r.number !=
                            null
                        ) {
                            "There's no part ${r.number}: give it a name to create it"
                        } else {
                            "Give a number or a name"
                        }
                    actions += null
                }
                else -> {
                    actions += ImportAction.CREATE
                    if (r.number != null &&
                        !manual
                    ) {
                        w += "New part numbers are assigned automatically: ${r.number} won't be kept"
                    }
                }
            }
            parts += part
        }
        val keys = rows.mapIndexed { i, r -> key(actions[i], parts[i]?.id?.value, r) }
        val rootKey = "id:${root.id.value}"
        val assemblyRows = rows.indices.filter { i -> parents.any { it == i } }.toSet()
        val defined = mutableSetOf<String>()
        for (i in rows.indices) {
            val k = keys[i] ?: continue
            // A part can't use itself, directly or further down.
            var p = parents[i]
            var cycle = k == rootKey
            while (p != null && !cycle) {
                cycle = keys[p] == k
                p = parents[p]
            }
            if (cycle) errors[i] += "This part would use itself"
            if (i in assemblyRows && !defined.add(k)) {
                errors[i] += "This part's structure is already given above: give it once"
            }
            if (i in assemblyRows && actions[i] == ImportAction.MATCH) errors[i] += editable(actor, parts[i]!!)
        }
        val rootProblems = editable(actor, root)
        if (rows.isEmpty()) throw ValidationException("INVALID_IMPORT_FILE", "The file has no lines")
        val previewRows =
            rows.mapIndexed { i, r ->
                PreviewRow(
                    row = r,
                    action = actions[i],
                    partId = parts[i]?.id?.value,
                    partNumber = parts[i]?.number,
                    partName = parts[i]?.name,
                    assembly = i in assemblyRows,
                    errors = errors[i] + if (i == 0) rootProblems else emptyList(),
                    warnings = warnings[i],
                )
            }
        return ImportPlan(
            ImportPreview(
                rootId = root.id.value,
                rootNumber = root.number,
                rows = previewRows,
                creates =
                    rows.indices
                        .filter { actions[it] == ImportAction.CREATE }
                        .mapNotNull { keys[it] }
                        .toSet()
                        .size,
                assemblies = 1 + assemblyRows.mapNotNull { keys[it] }.toSet().size,
                errors = previewRows.count { it.errors.isNotEmpty() },
            ),
            parents,
            keys,
            root.contextId.value,
        )
    }

    /** Why the import couldn't change [part]'s structure; empty when it can. */
    private fun editable(
        actor: Actor,
        part: VersionedObject,
    ): List<String> {
        val by = part.checkedOutBy
        if (by != null && by != actor.principal.value) return listOf("${part.number} is checked out by someone else")
        val lifecycle = versioning.lifecycle(actor, part.id)
        if (!lifecycle.editable && by == null) return listOf("${part.number} is ${lifecycle.state}: revise it first")
        return emptyList()
    }

    private fun isPart(
        actor: Actor,
        o: VersionedObject,
    ) = types.get(actor, o.typeId).baseClass == BaseClass.PART

    private fun requirePart(
        actor: Actor,
        o: VersionedObject,
    ) {
        if (!isPart(actor, o)) throw ValidationException("NOT_A_PART", "Only parts have structure")
    }

    /** Units of use by lower-case key and label (any language) → key. */
    private fun unitNames(actor: Actor): Map<String, String> {
        val values =
            enumerations
                .list(actor)
                .firstOrNull { it.name == "unitOfUse" }
                ?.values
                ?.filter { it.active }
                ?: return mapOf("each" to "each")
        return values.flatMap { v -> v.labels.values.map { it.lowercase() to v.key } }.toMap() +
            values.associate { it.key.lowercase() to it.key }
    }
}

/**
 * Runs a structure import (roadmap 4.13): creates the new parts, then sets each assembly's
 * structure, deepest first, each in its own transaction (check out, change lines, check in).
 */
@Component
internal class StructureImportJob(
    private val service: DefaultStructureImportService,
    private val versioning: VersioningService,
    private val bom: BomService,
    private val tx: TransactionTemplate,
) : JobHandler {
    data class Request(
        val rootId: UUID,
        val rootNumber: String,
        val fileName: String?,
        val rows: List<ImportRow>,
    )

    override val type = TYPE
    override val label = "Structure import"
    override val description = "Creates parts and sets structures from a CSV or Excel file"
    override val adminOnly = false

    // Re-running would repeat what already succeeded: one attempt, results per object instead.
    override val maxAttempts = 1

    override fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) {
        if (request(payload).rows.isEmpty()) throw ValidationException("INVALID_IMPORT", "Nothing to import")
    }

    override fun title(payload: Map<String, Any?>): String {
        val r = request(payload)
        val lines = if (r.rows.size == 1) "1 line" else "${r.rows.size} lines"
        return "Import ${r.fileName ?: "structure"} into ${r.rootNumber}: $lines"
    }

    override fun run(context: JobContext): Map<String, Any?> {
        val r = request(context.payload)
        val actor = context.actor
        val plan = service.plan(actor, MasterId(r.rootId), r.rows)
        if (plan.preview.errors > 0) throw JobPermanentFailure("The file has ${plan.preview.errors} lines with errors")
        val rows = plan.preview.rows
        val ids = mutableMapOf<String, UUID>()
        val numbers = mutableMapOf<String, String>()
        rows.forEach { p -> if (p.action == ImportAction.MATCH) ids[plan.keys[rows.indexOf(p)]!!] = p.partId!! }
        val results = mutableListOf<Map<String, Any?>>()
        val creates = rows.indices.filter { rows[it].action == ImportAction.CREATE }.distinctBy { plan.keys[it] }
        val assemblies = rows.indices.filter { rows[it].assembly }.sortedByDescending { rows[it].row.level }
        val total = creates.size + assemblies.size + 1
        var done = 0

        fun record(
            id: UUID?,
            number: String?,
            ok: Boolean,
            message: String,
        ) {
            results += mapOf("objectId" to id?.toString(), "number" to number, "ok" to ok, "message" to message)
            if (!ok) context.log("${number ?: "?"}: $message", "WARN")
            done++
            context.progress(
                done,
                total,
                "${results.count { it["ok"] == true }} done, ${results.count { it["ok"] == false }} failed",
            )
        }

        val manual = service.manualNumbers(actor)
        for (i in creates) {
            context.checkCancelled()
            val row = rows[i].row
            try {
                val created =
                    tx.execute {
                        versioning.create(
                            actor,
                            CreateVersionedObject(
                                TypeId.PART,
                                com.plm.access.api
                                    .ContextId(plan.contextId),
                                row.name!!,
                                number = row.number?.takeIf { manual },
                            ),
                        )
                    }
                ids[plan.keys[i]!!] = created.id.value
                numbers[plan.keys[i]!!] = created.number
                record(created.id.value, created.number, true, "Created")
            } catch (e: PlmException) {
                record(null, row.number ?: row.name, false, "Row ${row.row}: ${e.message}")
            }
        }

        fun sync(
            parent: UUID,
            number: String,
            children: List<Int>,
        ) {
            context.checkCancelled()
            val lines = children.map { j -> ids[plan.keys[j]] to rows[j].row }
            if (lines.any { it.first == null }) {
                record(parent, number, false, "Not changed: some of its parts couldn't be created")
                return
            }
            try {
                val message =
                    tx.execute {
                        apply(
                            actor,
                            MasterId(parent),
                            lines.map { it.first!! to it.second },
                            r.fileName,
                        )
                    }
                record(parent, number, true, message)
            } catch (e: PlmException) {
                record(parent, number, false, e.message ?: e.code)
            }
        }
        for (i in assemblies) {
            val id = ids[plan.keys[i]] ?: continue
            sync(id, rows[i].partNumber ?: numbers[plan.keys[i]] ?: "?", rows.indices.filter { plan.parents[it] == i })
        }
        sync(r.rootId, plan.preview.rootNumber, rows.indices.filter { plan.parents[it] == null })
        return mapOf(
            "succeeded" to results.count { it["ok"] == true },
            "failed" to results.count { it["ok"] == false },
            "results" to results,
        )
    }

    /** Makes [parent]'s lines exactly [children]: updates the matching ones, adds and removes the rest. */
    private fun apply(
        actor: Actor,
        parent: MasterId,
        children: List<Pair<UUID, ImportRow>>,
        fileName: String?,
    ): String {
        val obj = versioning.get(actor, parent)
        val mine = obj.checkedOutBy == actor.principal.value
        if (obj.checkedOutBy != null && !mine) throw ConflictException("CHECKED_OUT", "Checked out by someone else")
        if (!mine) versioning.checkOut(actor, parent)
        val existing = bom.usages(actor, parent, workingCopy = true).toMutableList()
        var added = 0
        var updated = 0
        for ((childId, row) in children) {
            val match =
                existing.firstOrNull {
                    it.child.id == childId &&
                        row.findNumber != null &&
                        it.findNumber == row.findNumber
                }
                    ?: existing.firstOrNull { it.child.id == childId }
            if (match == null) {
                bom.add(
                    actor,
                    parent,
                    AddUsage(
                        childId,
                        row.quantity ?: BigDecimal.ONE,
                        row.unit,
                        null,
                        row.findNumber,
                        row.notes,
                        row.designators,
                    ),
                )
                added++
                continue
            }
            existing.remove(match)
            val quantity = row.quantity ?: BigDecimal.ONE
            val unit = row.unit ?: match.unit
            val find = row.findNumber ?: match.findNumber
            val notes = row.notes ?: match.notes
            val same =
                quantity.compareTo(match.quantity) == 0 &&
                    unit == match.unit &&
                    find == match.findNumber &&
                    notes == match.notes &&
                    Designators.parse(row.designators) == match.designators
            if (!same) {
                bom.update(
                    actor,
                    parent,
                    match.id,
                    UpdateUsage(quantity, unit, match.lineNumber, find, notes, row.designators),
                )
                updated++
            }
        }
        existing.forEach { bom.remove(actor, parent, it.id) }
        val removed = existing.size
        if (added + updated + removed == 0) {
            if (!mine) versioning.undoCheckOut(actor, parent)
            return "Structure unchanged"
        }
        val summary =
            listOfNotNull(
                "$added added".takeIf { added > 0 },
                "$updated changed".takeIf { updated > 0 },
                "$removed removed".takeIf { removed > 0 },
            ).joinToString(", ")
        if (mine) return "Structure set in your working copy: $summary"
        val checkedIn = versioning.checkIn(actor, parent, "Imported from ${fileName ?: "a file"}")
        return "Structure set: $summary (now ${checkedIn.latest.version})"
    }

    companion object {
        const val TYPE = "bom.structure-import"
        private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        @Suppress("UNCHECKED_CAST")
        fun payload(r: Request): Map<String, Any?> = json.convertValue(r, Map::class.java) as Map<String, Any?>

        fun request(payload: Map<String, Any?>): Request =
            try {
                json.convertValue(payload, Request::class.java)
            } catch (e: Exception) {
                throw ValidationException("INVALID_IMPORT", "Not a structure import: ${e.message?.take(200)}")
            }
    }
}
