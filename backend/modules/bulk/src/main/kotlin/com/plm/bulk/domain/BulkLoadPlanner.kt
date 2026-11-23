package com.plm.bulk.domain

import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.bulk.api.LoadAction
import com.plm.bulk.api.LoadColumn
import com.plm.bulk.api.LoadFile
import com.plm.bulk.api.LoadPreview
import com.plm.bulk.api.LoadRow
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.sheets.Tables
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeValidator
import com.plm.types.api.BaseClass
import com.plm.types.api.DataType
import com.plm.types.api.EnumerationService
import com.plm.types.api.TypeDefinition
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeService
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipInputStream

private const val MAX_ROWS = 5_000
private const val MAX_ARCHIVE = 500L * 1024 * 1024

/** Header titles of the fixed columns, lower case, in the interface languages. */
private val FIXED =
    mapOf(
        "type" to setOf("type", "typ"),
        "number" to setOf("number", "numéro", "nummer"),
        "name" to setOf("name", "nom"),
        "folder" to setOf("folder", "dossier", "ordner"),
        "file" to setOf("file", "fichier", "datei"),
        "parent" to setOf("parent", "parent number", "übergeordnet"),
        "quantity" to setOf("quantity", "qty", "quantité", "menge"),
        "findNumber" to setOf("find number", "find no.", "repère", "positionsnummer"),
        "documents" to setOf("documents", "described by", "dokumente"),
        "state" to setOf("state", "état", "status", "zustand"),
    )

/** A row as the job needs it, beyond what the preview shows. */
internal data class PlannedRow(
    val view: LoadRow,
    val typeId: UUID?,
    val baseClass: BaseClass?,
    val existing: VersionedObject?,
    /** The number to give a new object, when the type allows manual numbers. */
    val keepNumber: Boolean,
    val quantity: BigDecimal,
    val findNumber: String?,
    val documents: List<String>,
    val state: String?,
)

internal data class LoadPlan(
    val preview: LoadPreview,
    val rows: List<PlannedRow>,
    val files: Map<String, ByteArray>,
)

/** Reads a load and works out, row by row, what it would do (roadmap 9.2). */
@Component
internal class BulkLoadPlanner(
    private val versioning: VersioningService,
    private val organizing: OrganizingService,
    private val contexts: ContextService,
    private val types: TypeService,
    private val attributes: AttributeService,
    private val validator: AttributeValidator,
    private val enumerations: EnumerationService,
    private val typeRules: TypeRuleService,
) {
    /** The object numbered [number], if the actor can see it. */
    fun find(
        actor: Actor,
        number: String,
    ): VersionedObject? =
        versioning.list(actor, ObjectQuery(text = number, limit = 50)).firstOrNull {
            it.number.equals(number, true)
        }

    fun plan(
        actor: Actor,
        contextId: UUID,
        sheet: LoadFile,
        archive: LoadFile?,
    ): LoadPlan {
        val context = contexts.get(actor, ContextId(contextId))
        val table = Tables.read(sheet.name, sheet.bytes)
        val header = table.first().map { it.trim() }
        val fixed = mutableMapOf<String, Int>()
        header.forEachIndexed { i, h ->
            val key = FIXED.entries.firstOrNull { h.lowercase() in it.value }?.key
            if (key != null && key !in fixed) fixed[key] = i
        }
        if ("type" !in fixed) throw ValidationException("INVALID_IMPORT_FILE", "The sheet needs a Type column")
        if ("name" !in fixed && "number" !in fixed) {
            throw ValidationException("INVALID_IMPORT_FILE", "The sheet needs a Name or a Number column")
        }
        val body = table.drop(1).withIndex().filter { (_, cells) -> cells.any { it.isNotBlank() } }
        if (body.size >
            MAX_ROWS
        ) {
            throw ValidationException("INVALID_IMPORT_FILE", "At most $MAX_ROWS rows can be loaded at once")
        }
        val files = archive?.let(::unzip) ?: emptyMap()

        val allTypes = types.list(actor)
        val typeByText = mutableMapOf<String, TypeDefinition?>()

        fun typeOf(text: String) =
            typeByText.getOrPut(text.lowercase()) {
                allTypes.firstOrNull { t ->
                    t.name.equals(text, true) ||
                        t.displayName.equals(text, true) ||
                        t.labels.values.any { it.equals(text, true) } ||
                        t.id.value.toString() == text
                }
            }
        val defs = mutableMapOf<UUID, List<AttributeDefinition>>()

        fun defsOf(type: TypeDefinition) =
            defs.getOrPut(type.id.value) {
                attributes.effective(actor, type.id).filter { it.active }
            }
        val existingByNumber = mutableMapOf<String, VersionedObject?>()

        fun existing(number: String) =
            existingByNumber.getOrPut(number.lowercase()) {
                versioning
                    .list(
                        actor,
                        ObjectQuery(text = number, limit = 50),
                    ).firstOrNull { it.number.equals(number, true) }
            }
        val manual = mutableMapOf<UUID, Boolean>()

        // Columns other than the fixed ones are attributes: by name, or label in any language.
        val attributeColumns = header.indices.filter { it !in fixed.values && header[it].isNotBlank() }
        val columns =
            header.mapIndexed { i, h ->
                val f = fixed.entries.firstOrNull { it.value == i }?.key
                when {
                    f != null -> LoadColumn(h, f)
                    h.isBlank() -> LoadColumn(h, "ignored")
                    else -> LoadColumn(h, "attribute")
                }
            }

        val folderCache = mutableMapOf<String, Boolean>()
        val newFolders = sortedSetOf<String>()

        fun folderExists(path: String): Boolean =
            folderCache.getOrPut(path) {
                var parent: UUID? = null
                for (part in path.split('/').filter { it.isNotBlank() }) {
                    parent = organizing.folders(actor, contextId, parent).firstOrNull { it.name.equals(part, true) }?.id
                        ?: return@getOrPut false
                }
                true
            }

        val numbersInLoad = mutableMapOf<String, Int>()
        val planned =
            body.map { (i, cells) ->
                val rowNumber = i + 2

                fun cell(key: String) = fixed[key]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
                val errors = mutableListOf<String>()
                val warnings = mutableListOf<String>()
                val typeText = cell("type")
                val type = typeText?.let(::typeOf)
                when {
                    typeText == null -> errors += "Give a type"
                    type == null -> errors += "There is no type '$typeText'"
                    !type.instantiable -> errors += "${type.displayName} only groups other types: choose one of them"
                }
                val number = cell("number")
                val name = cell("name")
                if (number != null) {
                    val first = numbersInLoad.putIfAbsent(number.lowercase(), rowNumber)
                    if (first != null) errors += "$number is already on row $first"
                }
                val found = number?.let(::existing)
                if (found != null && type != null && found.typeId.value != type.id.value) {
                    errors += "$number exists as another type"
                }
                if (found == null && name == null) errors += "Give a name for a new object"
                if (found != null && name != null && name != found.name) {
                    warnings += "The name stays ${found.name}: rename it on its page"
                }
                if (found?.checkedOutBy != null && found.checkedOutBy != actor.principal.value) {
                    errors += "$number is checked out by someone else"
                }
                val keep =
                    type != null &&
                        found == null &&
                        number != null &&
                        manual.getOrPut(type.id.value) { typeRules.rules(actor, type.id).allowManualNumber }
                if (type != null && found == null && number != null && !keep) {
                    warnings += "${type.displayName} numbers are assigned automatically: $number won't be kept"
                }

                // Attribute values, converted from text and then checked like a form would.
                val values = linkedMapOf<String, Any?>()
                if (type != null) {
                    val typeDefs = defsOf(type)
                    for (c in attributeColumns) {
                        val text = cells.getOrNull(c)?.trim().orEmpty()
                        val h = header[c]
                        val def =
                            typeDefs.firstOrNull { d ->
                                d.name.equals(h, true) || d.labels.values.any { it.equals(h, true) }
                            }
                        if (def == null) {
                            if (text.isNotEmpty()) {
                                warnings +=
                                    "'$h' is not an attribute of ${type.displayName}: ignored"
                            }
                            continue
                        }
                        if (text.isEmpty()) continue
                        if (def.formula != null || def.aliasOf != null) {
                            warnings += "${def.name} is calculated: the value is ignored"
                            continue
                        }
                        val value = convert(actor, def, text, ::existing)
                        if (value ==
                            null
                        ) {
                            errors += "${def.name}: '$text' isn't a valid ${def.dataType.name.lowercase()}"
                        } else {
                            values[def.name] =
                                value
                        }
                    }
                    if (errors.isEmpty()) {
                        try {
                            validator.validate(
                                actor,
                                type.id,
                                (found?.latest?.attributes ?: emptyMap()) + values,
                                found?.latest?.attributes,
                            )
                        } catch (e: ValidationException) {
                            @Suppress("UNCHECKED_CAST")
                            val list = e.details["errors"] as? List<Map<String, Any?>>
                            errors +=
                                list?.map { it["message"]?.toString() ?: e.message.orEmpty() }
                                    ?: listOf(e.message.orEmpty())
                        }
                    }
                }

                val folder =
                    cell("folder")?.let {
                        "/" +
                            it
                                .trim('/')
                                .split('/')
                                .filter(String::isNotBlank)
                                .joinToString("/")
                    }
                if (folder != null && folder != "/") {
                    var path = ""
                    for (part in folder.trim('/').split('/')) {
                        path += "/$part"
                        if (!folderExists(path)) newFolders += path
                    }
                }
                val file = cell("file")?.replace('\\', '/')?.trimStart('/', '.')
                if (file != null && fileIn(files, file) == null) {
                    errors += if (archive == null) "Add the zip with $file" else "$file isn't in the zip"
                }
                val quantityText = cell("quantity")
                val quantity = quantityText?.replace(',', '.')?.toBigDecimalOrNull() ?: BigDecimal.ONE
                if (quantityText != null &&
                    (quantityText.replace(',', '.').toBigDecimalOrNull() ?: BigDecimal.ZERO).signum() <= 0
                ) {
                    errors += "The quantity must be a number above 0"
                }
                val documents =
                    cell("documents")?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

                val changes = values.isNotEmpty() || file != null || cell("state") != null
                val action =
                    when {
                        errors.isNotEmpty() -> LoadAction.ERROR
                        found == null -> LoadAction.CREATE
                        changes -> LoadAction.UPDATE
                        else -> LoadAction.UNCHANGED
                    }
                if (found != null &&
                    changes &&
                    errors.isEmpty() &&
                    !versioning.lifecycle(actor, found.id).editable &&
                    found.checkedOutBy == null
                ) {
                    warnings +=
                        "${found.number} is ${found.latest.state}: it changes in a new iteration of that version"
                }
                PlannedRow(
                    LoadRow(
                        row = rowNumber,
                        action = action,
                        type = type?.displayName ?: typeText,
                        number = found?.number ?: number,
                        name = found?.name ?: name,
                        objectId = found?.id?.value,
                        folder = folder,
                        file = file,
                        parent = cell("parent"),
                        attributes = values,
                        errors = errors,
                        warnings = warnings,
                    ),
                    type?.id?.value,
                    type?.baseClass,
                    found,
                    keep,
                    quantity,
                    cell("findNumber"),
                    documents,
                    cell("state"),
                )
            }

        // Structure and links: parents and documents in the load or already in the system.
        val inLoad = planned.filter { it.view.number != null }.associateBy { it.view.number!!.lowercase() }

        fun baseOf(number: String): BaseClass? =
            inLoad[number.lowercase()]?.baseClass
                ?: existing(number)?.let { o -> allTypes.firstOrNull { it.id == o.typeId }?.baseClass }
        val edges = mutableMapOf<String, MutableList<String>>()
        val withLinks =
            planned.map { p ->
                val errors = p.view.errors.toMutableList()
                val parent = p.view.parent
                if (parent != null) {
                    val base = baseOf(parent)
                    when {
                        base == null -> errors += "There is no part $parent (in the sheet or the system)"
                        base != BaseClass.PART -> errors += "$parent isn't a part"
                        p.baseClass != BaseClass.PART -> errors += "Only parts go into a structure"
                        p.view.number == null -> errors += "Give a number to put it under $parent"
                        else -> {
                            edges.getOrPut(parent.lowercase()) { mutableListOf() } += p.view.number.lowercase()
                            val existingParent = inLoad[parent.lowercase()]?.existing ?: existing(parent)
                            if (existingParent != null && inLoad[parent.lowercase()] == null) {
                                val by = existingParent.checkedOutBy
                                if (by != null && by != actor.principal.value) {
                                    errors += "$parent is checked out by someone else"
                                } else if (by == null && !versioning.lifecycle(actor, existingParent.id).editable) {
                                    errors += "$parent is ${existingParent.latest.state}: revise it first"
                                }
                            }
                        }
                    }
                }
                for (d in p.documents) {
                    val base = baseOf(d)
                    when {
                        p.baseClass != BaseClass.PART -> errors += "Only parts link to documents"
                        base == null -> errors += "There is no document $d (in the sheet or the system)"
                        base != BaseClass.DOCUMENT -> errors += "$d isn't a document"
                    }
                }
                if (errors.size == p.view.errors.size) {
                    p
                } else {
                    p.copy(view = p.view.copy(errors = errors, action = LoadAction.ERROR))
                }
            }
        // A part can't end up using itself.
        val cyclic = cycles(edges)
        val rows =
            withLinks.map { p ->
                if (p.view.number?.lowercase() in cyclic) {
                    p.copy(
                        view =
                            p.view.copy(
                                errors = p.view.errors + "This structure would use itself",
                                action = LoadAction.ERROR,
                            ),
                    )
                } else {
                    p
                }
            }
        val views = rows.map { it.view }
        return LoadPlan(
            LoadPreview(
                fileName = sheet.name,
                contextId = contextId,
                contextName = context.name,
                columns = columns,
                rows = views,
                creates = views.count { it.action == LoadAction.CREATE },
                updates = views.count { it.action == LoadAction.UPDATE },
                errors = views.count { it.action == LoadAction.ERROR },
                files = views.count { it.file != null },
                usages = views.count { it.parent != null },
                links = rows.sumOf { it.documents.size },
                newFolders = newFolders.toList(),
            ),
            rows,
            files,
        )
    }

    /** Numbers on a cycle of the load's parent → child edges. */
    private fun cycles(edges: Map<String, List<String>>): Set<String> {
        val onCycle = mutableSetOf<String>()
        val state = mutableMapOf<String, Int>()

        fun visit(
            n: String,
            path: ArrayDeque<String>,
        ) {
            when (state[n]) {
                1 -> {
                    onCycle += path.dropWhile { it != n }
                    return
                }
                2 -> return
            }
            state[n] = 1
            path.addLast(n)
            edges[n]?.forEach { visit(it, path) }
            path.removeLast()
            state[n] = 2
        }
        edges.keys.forEach { visit(it, ArrayDeque()) }
        return onCycle
    }

    /** The cell's text as a value of [def]'s type, or null when it doesn't fit. */
    private fun convert(
        actor: Actor,
        def: AttributeDefinition,
        text: String,
        existing: (String) -> VersionedObject?,
    ): Any? {
        if (def.multiValued) {
            val parts = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val values = parts.map { one(actor, def, it, existing) }
            return if (values.any { it == null }) null else values
        }
        return one(actor, def, text, existing)
    }

    private fun one(
        actor: Actor,
        def: AttributeDefinition,
        text: String,
        existing: (String) -> VersionedObject?,
    ): Any? =
        when (def.dataType) {
            DataType.STRING, DataType.URL, DataType.TEXT -> text
            DataType.INTEGER ->
                text
                    .replace(" ", "")
                    .toBigDecimalOrNull()
                    ?.takeIf {
                        it.stripTrailingZeros().scale() <=
                            0
                    }?.toLong()
            DataType.REAL -> decimal(text)?.toDouble()
            DataType.BOOLEAN ->
                when (text.lowercase()) {
                    "true", "yes", "y", "1", "x", "oui", "ja", "vrai", "wahr" -> true
                    "false", "no", "n", "0", "non", "nein", "faux", "falsch" -> false
                    else -> null
                }
            DataType.DATE ->
                runCatching { LocalDate.parse(text).toString() }.getOrNull()
                    // Excel keeps dates as days since 1899-12-30.
                    ?: text.toBigDecimalOrNull()?.let { LocalDate.of(1899, 12, 30).plusDays(it.toLong()).toString() }
            DataType.TIMESTAMP ->
                runCatching { Instant.parse(text).toString() }.getOrNull()
                    ?: runCatching {
                        LocalDate
                            .parse(
                                text,
                            ).atStartOfDay()
                            .toInstant(java.time.ZoneOffset.UTC)
                            .toString()
                    }.getOrNull()
            DataType.QUANTITY -> {
                val m = Regex("""^([-+]?[0-9][0-9 .,]*)\s*([^\d\s].*)?$""").matchEntire(text)
                val value = m?.groupValues?.get(1)?.let(::decimal)
                val unit =
                    m
                        ?.groupValues
                        ?.get(2)
                        ?.trim()
                        ?.ifEmpty { null } ?: def.displayUnit
                if (value == null || unit == null) null else mapOf("value" to value, "unit" to unit)
            }
            DataType.ENUMERATION ->
                def.enumerationId?.let { id ->
                    enumerations
                        .get(actor, id)
                        .values
                        .firstOrNull { v ->
                            v.active &&
                                (v.key.equals(text, true) || v.labels.values.any { it.equals(text, true) })
                        }?.key
                }
            DataType.REFERENCE -> existing(text)?.id?.value?.toString()
        }

    private fun decimal(text: String): BigDecimal? {
        val t = text.replace(" ", "").replace(" ", "")
        return (
            if (t.contains(',') &&
                !t.contains('.')
            ) {
                t.replace(',', '.')
            } else {
                t.replace(",", "")
            }
        ).toBigDecimalOrNull()
    }

    /** The zip's files by path; folders and macOS extras left out. */
    private fun unzip(archive: LoadFile): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(archive.bytes)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    if (e.isDirectory ||
                        e.name.startsWith("__MACOSX/") ||
                        e.name.substringAfterLast('/').startsWith("._")
                    ) {
                        return@forEach
                    }
                    val data = zip.readAllBytes()
                    total += data.size
                    if (total >
                        MAX_ARCHIVE
                    ) {
                        throw ValidationException("INVALID_IMPORT_FILE", "The zip holds more than 500 MB")
                    }
                    out[e.name.replace('\\', '/').trimStart('/')] = data
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw ValidationException("INVALID_IMPORT_FILE", "The files must come as a .zip").apply { initCause(e) }
        }
        return out
    }

    companion object {
        /** A file named in the sheet: by its path in the zip, else by its name when only one file has it. */
        fun fileIn(
            files: Map<String, ByteArray>,
            path: String,
        ): Pair<String, ByteArray>? {
            files.entries.firstOrNull { it.key.equals(path, true) }?.let { return it.key to it.value }
            val byName =
                files.entries.filter {
                    it.key
                        .substringAfterLast(
                            '/',
                        ).equals(path.substringAfterLast('/'), true)
                }
            return byName.singleOrNull()?.let { it.key to it.value }
        }
    }
}
