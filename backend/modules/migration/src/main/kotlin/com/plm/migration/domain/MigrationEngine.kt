package com.plm.migration.domain

import com.plm.access.api.ContextId
import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.PastUsage
import com.plm.bom.api.StructureHistoryImportService
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.ProvisionPrincipal
import com.plm.lifecycle.api.LifecycleService
import com.plm.migration.api.MigrationAnalysis
import com.plm.migration.api.MigrationIssue
import com.plm.migration.api.MigrationMappings
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeValidator
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeDefinition
import com.plm.types.api.TypeService
import com.plm.versioning.api.HistoryImportService
import com.plm.versioning.api.ImportObjectHistory
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.PastIteration
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.net.URLConnection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipFile

private const val MAX_ISSUES = 500

/** A legacy object as the current mappings translate it. */
internal data class Translated(
    val source: LegacyObject,
    val type: TypeDefinition?,
    /** Per iteration: state key here, attributes here, author's principal id. */
    val iterations: List<PastIteration>,
    val errors: List<String>,
)

/** Analyses (dry run) and runs migrations (roadmap 9.3). */
@Component
internal class MigrationEngine(
    private val types: TypeService,
    private val attributes: AttributeService,
    private val validator: AttributeValidator,
    private val lifecycles: LifecycleService,
    private val principals: PrincipalDirectory,
    private val versioning: VersioningService,
    private val history: HistoryImportService,
    private val organizing: OrganizingService,
    private val content: ContentService,
    private val structure: StructureHistoryImportService,
    private val bom: com.plm.bom.api.BomService,
    private val links: com.plm.bom.api.DocumentLinkService,
    private val tx: TransactionTemplate,
) {
    private class Lookup(
        val actor: Actor,
        val all: List<TypeDefinition>,
        val engine: MigrationEngine,
    ) {
        val states = mutableMapOf<UUID, Set<String>>()

        fun type(name: String) =
            all.firstOrNull { t ->
                t.instantiable &&
                    (
                        t.name.equals(name, true) ||
                            t.displayName.equals(name, true) ||
                            t.labels.values.any { it.equals(name, true) }
                    )
            }

        /** Every state of any lifecycle: for objects whose type isn't mapped yet. */
        val anyState by lazy { all.filter { it.instantiable }.flatMap { states(it) }.toSet() }

        fun states(t: TypeDefinition) =
            states.getOrPut(t.id.value) {
                engine.lifecycles
                    .forType(actor, t.id)
                    .states
                    .map { it.key }
                    .toSet()
            }

        private val defs = mutableMapOf<UUID, List<com.plm.types.api.AttributeDefinition>>()

        /** The attribute of [t] called [name] (its name or a label, any case), if any. */
        fun attribute(
            t: TypeDefinition,
            name: String,
        ): String? =
            defs
                .getOrPut(t.id.value) {
                    engine.attributes.effective(actor, t.id).filter {
                        it.active &&
                            it.formula == null &&
                            it.aliasOf == null
                    }
                }.firstOrNull { d ->
                    d.name.equals(name, true) || d.labels.values.any { it.equals(name, true) }
                }?.name
    }

    /** Legacy user id → principal id here, as the mappings and the manifest say (no changes made). */
    private fun authorOf(
        actor: Actor,
        manifest: Manifest,
        mappings: MigrationMappings,
        legacy: String?,
        known: MutableMap<String, String>,
    ): String {
        val id = legacy ?: return actor.principal.value
        return known.getOrPut(id) {
            mappings.users[id]?.let { username -> principals.findByUsername(actor, username)?.id?.value }
                ?: manifest.users.firstOrNull { it.id == id }?.let { u ->
                    principals.findByUsername(actor, u.username)?.id?.value ?: u.username
                }
                ?: "legacy:$id"
        }
    }

    private fun instant(text: String): Instant? =
        runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()

    fun translate(
        actor: Actor,
        manifest: Manifest,
        mappings: MigrationMappings,
        files: Set<String>,
        unmapped: MutableMap<String, MutableMap<String, Int>>? = null,
        warnings: MutableList<MigrationIssue>? = null,
    ): List<Translated> {
        val lookup = Lookup(actor, types.list(actor), this)
        val authors = mutableMapOf<String, String>()
        val ids = manifest.objects.associateBy { it.id }
        val numbers = mutableSetOf<String>()

        fun count(
            kind: String,
            name: String,
        ) {
            unmapped?.getOrPut(kind) { mutableMapOf() }?.merge(name, 1, Int::plus)
        }
        return manifest.objects.map { o ->
            val errors = mutableListOf<String>()
            val typeName = mappings.types[o.type] ?: o.type
            val type = lookup.type(typeName)
            if (type == null) {
                count("types", o.type)
                errors += "No type here matches '${o.type}': map it"
            }
            if (!numbers.add(o.number.lowercase())) errors += "The number ${o.number} occurs twice in the package"
            if (o.iterations.isEmpty()) errors += "It has no iterations"
            val iterations =
                o.iterations.mapNotNull { i ->
                    val label = "${i.version}.${i.iteration}"
                    val state = mappings.states[i.state] ?: i.state
                    if (if (type != null) state !in lookup.states(type) else state !in lookup.anyState) {
                        count("states", i.state)
                        errors += "$label: no state '${i.state}' in its lifecycle: map it"
                    }
                    val at = instant(i.createdAt)
                    if (at == null) errors += "$label: '${i.createdAt}' isn't a date and time"
                    i.files.forEach { f ->
                        if (f.path.trimStart('/') !in
                            files
                        ) {
                            errors += "$label: ${f.path} isn't in the zip"
                        }
                    }
                    i.uses.forEach { u ->
                        val child = ids[u.child]
                        if (child == null) errors += "$label uses ${u.child}, which isn't in the package"
                        if (u.quantity.signum() <= 0) errors += "$label: quantities must be above 0"
                    }
                    i.describedBy.forEach { d ->
                        if (ids[d] ==
                            null
                        ) {
                            errors += "$label is described by $d, which isn't in the package"
                        }
                    }
                    val values = linkedMapOf<String, Any?>()
                    if (type != null) {
                        for ((name, value) in i.attributes) {
                            val target = if (mappings.attributes.containsKey(name)) mappings.attributes[name] else name
                            if (target.isNullOrBlank()) continue
                            val attribute = lookup.attribute(type, target)
                            if (attribute == null) {
                                count("attributes", name)
                                continue
                            }
                            if (value != null) values[attribute] = value
                        }
                    }
                    if (at == null) {
                        null
                    } else {
                        PastIteration(
                            i.version,
                            i.iteration,
                            state,
                            values,
                            i.comment,
                            at,
                            authorOf(actor, manifest, mappings, i.createdBy, authors),
                        )
                    }
                }
            if (type != null && errors.isEmpty()) {
                // Values are checked as on a check-in.
                for (it in iterations) {
                    try {
                        validator.validate(actor, type.id, it.attributes, previous = null)
                    } catch (e: ValidationException) {
                        @Suppress("UNCHECKED_CAST")
                        val list = e.details["errors"] as? List<Map<String, Any?>>
                        errors +=
                            "${it.revision}.${it.iteration}: " +
                            (list?.joinToString("; ") { m -> m["message"].toString() } ?: e.message)
                    }
                }
            }
            val use = o.iterations.flatMap { it.uses }
            if (type != null &&
                use.isNotEmpty() &&
                type.baseClass != BaseClass.PART
            ) {
                errors += "Only parts have structure"
            }
            if (o.iterations.any { it.files.isNotEmpty() } && type != null && type.baseClass == BaseClass.PART) {
                errors += "Files go on documents and CAD documents, not on parts"
            }
            Translated(o, type, iterations, errors)
        }
    }

    fun analyse(
        actor: Actor,
        contents: PackageContents,
        mappings: MigrationMappings,
        done: Set<String>,
    ): MigrationAnalysis {
        val m = contents.manifest
        val unmapped = mutableMapOf<String, MutableMap<String, Int>>()
        val warnings = mutableListOf<MigrationIssue>()
        val translated = translate(actor, m, mappings, contents.files, unmapped, warnings)
        val issues = mutableListOf<MigrationIssue>()
        for (t in translated) {
            if (t.source.id in done) continue
            t.errors.forEach { issues += MigrationIssue(t.source.id, t.source.number, "ERROR", it) }
            val number = numberFor(t.source, mappings)
            val here = find(actor, number)
            if (here != null) {
                when {
                    mappings.existing == "UPDATE" && t.type != null && here.typeId != t.type.id ->
                        issues +=
                            MigrationIssue(
                                t.source.id,
                                number,
                                "ERROR",
                                "$number is here as another type: it can't be updated",
                            )
                    mappings.existing == "UPDATE" ->
                        warnings +=
                            MigrationIssue(t.source.id, number, "WARNING", "$number is here: it gets a new iteration")
                    mappings.existing == "COPY" ->
                        issues +=
                            MigrationIssue(
                                t.source.id,
                                number,
                                "ERROR",
                                "The copy's number $number is taken too: choose another suffix",
                            )
                    else ->
                        issues +=
                            MigrationIssue(t.source.id, number, "ERROR", "An object numbered $number is already here")
                }
            }
        }
        unmapped["attributes"]?.forEach { (name, n) ->
            warnings +=
                MigrationIssue(
                    null,
                    null,
                    "WARNING",
                    "Attribute '$name' (×$n) matches nothing here: it will be left out",
                )
        }
        val referenced = m.objects.flatMap { o -> o.iterations.mapNotNull { it.createdBy } }.toSet()
        referenced.filter { id -> id !in mappings.users && m.users.none { it.id == id } }.forEach {
            warnings += MigrationIssue(null, null, "WARNING", "User '$it' isn't in the manifest: kept as legacy:$it")
        }
        val all = issues + warnings
        return MigrationAnalysis(
            source = m.source,
            objects = m.objects.size,
            iterations = m.objects.sumOf { it.iterations.size },
            files = m.objects.sumOf { o -> o.iterations.sumOf { it.files.size } },
            usages = m.objects.sumOf { o -> o.iterations.sumOf { it.uses.size } },
            documentLinks = m.objects.sumOf { o -> o.iterations.sumOf { it.describedBy.size } },
            users = m.users.size,
            alreadyDone = m.objects.count { it.id in done },
            unmappedTypes = unmapped["types"].orEmpty(),
            unmappedStates = unmapped["states"].orEmpty(),
            unmappedAttributes = unmapped["attributes"].orEmpty(),
            errors = issues.size,
            warnings = warnings.size,
            issues = all.take(MAX_ISSUES),
        )
    }

    /** The number an object gets here: its own, or its copy's. */
    fun numberFor(
        o: LegacyObject,
        mappings: MigrationMappings,
    ) = if (mappings.existing ==
        "COPY"
    ) {
        "${o.number}-${mappings.copySuffix?.trim()?.ifEmpty { null } ?: "R"}"
    } else {
        o.number
    }

    private fun find(
        actor: Actor,
        number: String,
    ) = versioning
        .list(
            actor,
            com.plm.versioning.api
                .ObjectQuery(text = number, limit = 20),
        ).firstOrNull { it.number.equals(number, true) }

    /** What happened to one object in a run. */
    data class Outcome(
        val legacyId: String,
        val number: String,
        val masterId: UUID?,
        val ok: Boolean,
        val message: String,
        /** An object already here got a new iteration. */
        val updated: Boolean = false,
    )

    /** Brings in the people who made the objects, then each object not yet done. */
    fun runObjects(
        actor: Actor,
        contextId: UUID,
        contents: PackageContents,
        zip: ZipFile,
        mappings: MigrationMappings,
        done: Set<String>,
        each: (Outcome) -> Unit,
        cancelled: () -> Unit,
    ) {
        val m = contents.manifest
        // People first: authors who aren't mapped to someone here.
        for (u in m.users) {
            if (u.id in mappings.users || principals.findByUsername(actor, u.username) != null) continue
            tx.execute {
                principals.provision(
                    actor,
                    ProvisionPrincipal(
                        PrincipalId(u.username),
                        u.username,
                        u.displayName ?: u.username,
                        u.email,
                        active = false,
                    ),
                )
            }
        }
        val folders = mutableMapOf<String, UUID?>()
        for (t in translate(actor, m, mappings, contents.files)) {
            cancelled()
            val o = t.source
            if (o.id in done) continue
            if (t.errors.isNotEmpty() || t.type == null) {
                each(Outcome(o.id, o.number, null, false, t.errors.joinToString("; ")))
                continue
            }
            val number = numberFor(o, mappings)
            val here = find(actor, number)
            if (here != null && mappings.existing == "UPDATE") {
                try {
                    tx.executeWithoutResult { update(actor, here.id, o, t, zip, m.source) }
                    each(Outcome(o.id, number, here.id.value, true, "Updated: a new iteration", updated = true))
                } catch (e: PlmException) {
                    each(Outcome(o.id, number, here.id.value, false, e.message ?: e.code))
                }
                continue
            }
            // A copy says where it came from.
            val iterations =
                if (mappings.existing == "COPY") {
                    t.iterations.map {
                        it.copy(
                            comment =
                                "Received from ${m.source ?: "a package"}" + (it.comment?.let { c -> ": $c" } ?: ""),
                        )
                    }
                } else {
                    t.iterations
                }
            try {
                val id =
                    tx.execute {
                        val folderId = o.folder?.let { folder(actor, contextId, it, folders) }
                        val imported =
                            history.importObject(
                                actor,
                                ImportObjectHistory(
                                    t.type.id,
                                    ContextId(contextId),
                                    number,
                                    o.name,
                                    folderId,
                                    iterations,
                                ),
                            )
                        o.iterations.forEachIndexed { i, legacy ->
                            for (f in legacy.files) {
                                val path = f.path.trimStart('/')
                                val entry =
                                    zip.getEntry(path)
                                        ?: throw ValidationException("INVALID_MIGRATION", "$path isn't in the zip")
                                val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                                val name = path.substringAfterLast('/')
                                content.importFile(
                                    actor,
                                    imported.id,
                                    imported.iterationIds[i].value,
                                    name,
                                    URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream",
                                    bytes,
                                    if (f.role.equals(
                                            "ATTACHMENT",
                                            true,
                                        )
                                    ) {
                                        ContentRole.ATTACHMENT
                                    } else {
                                        ContentRole.PRIMARY
                                    },
                                )
                            }
                        }
                        imported.id.value
                    }
                each(
                    Outcome(
                        o.id,
                        o.number,
                        id,
                        true,
                        "${t.iterations.size} iteration${if (t.iterations.size == 1) "" else "s"}",
                    ),
                )
            } catch (e: PlmException) {
                each(Outcome(o.id, o.number, null, false, e.message ?: e.code))
            }
        }
    }

    /** Structure and document links of every object brought in whose links aren't done. */
    fun runLinks(
        actor: Actor,
        contents: PackageContents,
        objects: Map<String, UUID>,
        pending: Set<String>,
        updated: Set<String>,
        each: (legacyId: String, ok: Boolean, message: String) -> Unit,
        cancelled: () -> Unit,
    ) {
        for (o in contents.manifest.objects) {
            if (o.id !in pending) continue
            if (o.iterations.none { it.uses.isNotEmpty() || it.describedBy.isNotEmpty() }) {
                each(o.id, true, "")
                continue
            }
            cancelled()
            val masterId = objects[o.id] ?: continue
            try {
                val counts =
                    tx.execute {
                        val id = MasterId(masterId)
                        // Iterations by label: the history as it came in.
                        val past = versioning.history(actor, id).associateBy { "${it.revision}.${it.iteration}" }
                        var lines = 0
                        var docs = 0
                        // An object updated here: the package's last iteration's links go on its latest.
                        val update = o.id in updated
                        val latest =
                            if (update) {
                                versioning
                                    .get(actor, id)
                                    .latest.id.value
                            } else {
                                null
                            }
                        val usedHere =
                            latest?.let { bom.usages(actor, id, it).map { l -> l.child.id }.toSet() } ?: emptySet()
                        val linkedHere =
                            latest?.let { links.links(actor, id, it).map { l -> l.document.id }.toSet() } ?: emptySet()
                        for (i in if (update) listOf(o.iterations.last()) else o.iterations) {
                            val iteration = latest ?: past["${i.version}.${i.iteration}"]?.id?.value ?: continue
                            val usages =
                                i.uses
                                    .map { u ->
                                        val child =
                                            objects[u.child]
                                                ?: throw ValidationException(
                                                    "INVALID_MIGRATION",
                                                    "${u.child} wasn't brought in",
                                                )
                                        PastUsage(child, u.quantity, u.unit, u.findNumber)
                                    }.filter { it.childId !in usedHere }
                            if (usages.isNotEmpty()) structure.importUsages(actor, id, iteration, usages)
                            lines += usages.size
                            for (d in i.describedBy) {
                                val doc =
                                    objects[d] ?: throw ValidationException("INVALID_MIGRATION", "$d wasn't brought in")
                                if (doc in linkedHere) continue
                                structure.importDocumentLink(
                                    actor,
                                    id,
                                    iteration,
                                    MasterId(doc),
                                    DocumentLinkKind.DESCRIBED_BY,
                                )
                                docs++
                            }
                        }
                        lines to docs
                    }
                each(o.id, true, "${counts.first} structure lines, ${counts.second} document links")
            } catch (e: PlmException) {
                each(o.id, false, e.message ?: e.code)
            }
        }
    }

    /** The package's last iteration of [o] as a new iteration of the object here. */
    private fun update(
        actor: Actor,
        id: MasterId,
        o: LegacyObject,
        t: Translated,
        zip: ZipFile,
        source: String?,
    ) {
        val last = t.iterations.last()
        val legacy = o.iterations.last()
        if (versioning.get(actor, id).checkedOutBy == null) versioning.checkOut(actor, id)
        val current = versioning.workingCopy(actor, id).attributes
        versioning.updateWorkingCopy(actor, id, current + last.attributes)
        for (f in legacy.files) {
            val path = f.path.trimStart('/')
            val entry = zip.getEntry(path) ?: throw ValidationException("INVALID_MIGRATION", "$path isn't in the zip")
            val name = path.substringAfterLast('/')
            content.store(
                actor,
                id,
                name,
                URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream",
                zip.getInputStream(entry).use { it.readAllBytes() },
                if (f.role.equals("ATTACHMENT", true)) ContentRole.ATTACHMENT else ContentRole.PRIMARY,
            )
        }
        versioning.checkIn(actor, id, "Received from ${source ?: "a package"} (${last.revision}.${last.iteration})")
    }

    private fun folder(
        actor: Actor,
        contextId: UUID,
        path: String,
        cache: MutableMap<String, UUID?>,
    ): UUID? {
        var parent: UUID? = null
        var walked = ""
        for (name in path.split('/').filter { it.isNotBlank() }) {
            walked += "/" + name.lowercase()
            val known = cache[walked]
            parent = known
                ?: (
                    organizing.folders(actor, contextId, parent).firstOrNull { it.name.equals(name, true) }?.id
                        ?: organizing.createFolder(actor, contextId, parent, name).id
                ).also { cache[walked] = it }
        }
        return parent
    }
}
