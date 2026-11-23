package com.plm.bulk.domain

import com.plm.access.api.ContextId
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.DocumentLinkService
import com.plm.bulk.api.BulkLoadService
import com.plm.bulk.api.LoadAction
import com.plm.bulk.api.LoadFile
import com.plm.bulk.api.LoadPreview
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.jobs.api.Job
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import com.plm.jobs.api.JobService
import com.plm.jobs.api.SubmitJob
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.types.api.AttributeService
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.net.URLConnection
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** The files of loads waiting for their job. */
@Component
internal class BulkLoadFiles(
    private val jdbc: JdbcClient,
) {
    data class Stored(
        val contextId: UUID,
        val sheet: LoadFile,
        val archive: LoadFile?,
    )

    fun save(
        actor: Actor,
        id: UUID,
        contextId: UUID,
        sheet: LoadFile,
        archive: LoadFile?,
        clock: Clock,
    ) {
        jdbc
            .sql(
                """
                insert into bulk_load (id, tenant_id, context_id, sheet_name, sheet, archive_name, archive, created_at, created_by)
                values (:id, :tenant, :context, :sheetName, :sheet, :archiveName, :archive, :now, :by)
                """,
            ).param("id", id)
            .param("tenant", actor.tenant.value)
            .param("context", contextId)
            .param("sheetName", sheet.name)
            .param("sheet", sheet.bytes)
            .param("archiveName", archive?.name)
            .param("archive", archive?.bytes)
            .param("now", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
    }

    fun load(id: UUID): Stored? =
        jdbc
            .sql("select * from bulk_load where id = :id")
            .param("id", id)
            .query { rs, _ ->
                Stored(
                    rs.getObject("context_id", UUID::class.java),
                    LoadFile(rs.getString("sheet_name"), rs.getBytes("sheet")),
                    rs.getString("archive_name")?.let { LoadFile(it, rs.getBytes("archive")) },
                )
            }.optional()
            .orElse(null)

    fun delete(id: UUID) {
        jdbc.sql("delete from bulk_load where id = :id").param("id", id).update()
    }
}

@Service
internal class DefaultBulkLoadService(
    private val planner: BulkLoadPlanner,
    private val files: BulkLoadFiles,
    private val jobs: JobService,
    private val types: TypeService,
    private val attributes: AttributeService,
    private val clock: Clock,
    private val tx: TransactionTemplate,
) : BulkLoadService {
    override fun preview(
        actor: Actor,
        contextId: UUID,
        sheet: LoadFile,
        archive: LoadFile?,
    ): LoadPreview = tx.execute { planner.plan(actor, contextId, sheet, archive).preview }

    override fun start(
        actor: Actor,
        contextId: UUID,
        sheet: LoadFile,
        archive: LoadFile?,
    ): Job =
        tx.execute {
            val preview = planner.plan(actor, contextId, sheet, archive).preview
            if (preview.errors > 0) {
                throw ValidationException(
                    "INVALID_LOAD",
                    "Fix the ${preview.errors} row${if (preview.errors == 1) "" else "s"} with errors first",
                )
            }
            if (preview.creates + preview.updates + preview.usages + preview.links == 0) {
                throw ValidationException("INVALID_LOAD", "Nothing to load")
            }
            val id = UuidV7.generate(clock)
            files.save(actor, id, contextId, sheet, archive, clock)
            jobs.submit(
                actor,
                SubmitJob(
                    BulkLoadJob.TYPE,
                    mapOf("loadId" to id.toString(), "fileName" to sheet.name, "rows" to preview.rows.size),
                ),
            )
        }

    override fun template(
        actor: Actor,
        typeId: UUID,
    ): String {
        val type = types.get(actor, TypeId(typeId))
        val attrs =
            attributes
                .effective(actor, type.id)
                .filter { it.active && it.formula == null && it.aliasOf == null }
                .sortedBy { it.sortOrder }
                .map { it.labels["en"] ?: it.name }
        val header =
            listOf(
                "Type",
                "Number",
                "Name",
                "Folder",
                "File",
                "Parent",
                "Quantity",
                "Find number",
                "Documents",
                "State",
            ) +
                attrs
        val example = listOf(type.displayName, "", "") + List(header.size - 3) { "" }
        return listOf(header, example).joinToString("\r\n") { row -> row.joinToString(",") { csvCell(it) } } + "\r\n"
    }

    private fun csvCell(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + s.replace("\"", "\"\"") +
                "\""
        } else {
            s
        }
}

/**
 * Runs a bulk load (roadmap 9.2): folders, then each object (created, or updated in a new
 * iteration, with its file and state), then each part's structure and document links, each step
 * in its own transaction so one failure doesn't undo the rest. The result lists every row.
 */
@Component
internal class BulkLoadJob(
    private val planner: BulkLoadPlanner,
    private val files: BulkLoadFiles,
    private val versioning: VersioningService,
    private val organizing: OrganizingService,
    private val content: ContentService,
    private val bom: BomService,
    private val links: DocumentLinkService,
    private val tx: TransactionTemplate,
) : JobHandler {
    override val type = TYPE
    override val label = "Bulk load"
    override val description = "Creates and updates objects, files, structure and links from a spreadsheet"
    override val adminOnly = false

    // Running again would repeat what succeeded: one attempt, with a result per row instead.
    override val maxAttempts = 1

    override fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) {
        if (payload["loadId"] == null) throw ValidationException("INVALID_LOAD", "Which load?")
    }

    override fun title(payload: Map<String, Any?>) = "Load ${payload["fileName"]}: ${payload["rows"]} rows"

    override fun run(context: JobContext): Map<String, Any?> {
        val loadId = UUID.fromString(context.payload["loadId"].toString())
        val stored = files.load(loadId) ?: throw JobPermanentFailure("The load's files are gone")
        val actor = context.actor
        val plan = tx.execute { planner.plan(actor, stored.contextId, stored.sheet, stored.archive) }
        if (plan.preview.errors > 0) {
            throw JobPermanentFailure("The sheet now has ${plan.preview.errors} rows with errors: check it again")
        }
        val results = mutableListOf<Map<String, Any?>>()
        val work = plan.rows.filter { it.view.action == LoadAction.CREATE || it.view.action == LoadAction.UPDATE }
        val parents = plan.rows.filter { it.view.parent != null }.groupBy { it.view.parent!!.lowercase() }
        val linkers = plan.rows.filter { it.documents.isNotEmpty() }
        val total = work.size + (parents.keys + linkers.mapNotNull { it.view.number?.lowercase() }).size
        var done = 0

        fun record(
            row: Int?,
            id: UUID?,
            number: String?,
            ok: Boolean,
            message: String,
        ) {
            results +=
                mapOf("row" to row, "objectId" to id?.toString(), "number" to number, "ok" to ok, "message" to message)
            if (!ok) context.log("${number ?: "row $row"}: $message", "WARN")
            done++
            context.progress(
                done,
                total,
                "${results.count { it["ok"] == true }} done, ${results.count { it["ok"] == false }} failed",
            )
        }

        // Folders first, made along each path as needed.
        val folderIds = mutableMapOf<String, UUID>()

        fun folder(path: String): UUID? {
            if (path == "/") return null
            folderIds[path.lowercase()]?.let { return it }
            var parent: UUID? = null
            var walked = ""
            for (name in path.trim('/').split('/')) {
                walked += "/$name"
                val known = folderIds[walked.lowercase()]
                val id =
                    known ?: tx.execute {
                        organizing
                            .folders(
                                actor,
                                stored.contextId,
                                parent,
                            ).firstOrNull { it.name.equals(name, true) }
                            ?.id
                            ?: organizing.createFolder(actor, stored.contextId, parent, name).id
                    }
                folderIds[walked.lowercase()] = id
                parent = id
            }
            return parent
        }

        val ids = mutableMapOf<String, UUID>()
        plan.rows.forEach { p -> p.existing?.let { ids[it.number.lowercase()] = it.id.value } }
        for (p in work) {
            context.checkCancelled()
            val row = p.view
            try {
                val folderId = row.folder?.let(::folder)
                val id =
                    tx.execute {
                        val id =
                            if (p.existing == null) {
                                versioning
                                    .create(
                                        actor,
                                        CreateVersionedObject(
                                            TypeId(p.typeId!!),
                                            ContextId(stored.contextId),
                                            row.name!!,
                                            number = row.number?.takeIf { p.keepNumber },
                                            attributes = row.attributes,
                                            folderId = folderId,
                                        ),
                                    ).id
                            } else {
                                p.existing.id
                            }
                        val file = row.file?.let { BulkLoadPlanner.fileIn(plan.files, it) }
                        if (p.existing != null &&
                            (row.attributes.isNotEmpty() || file != null) ||
                            p.existing == null &&
                            file != null
                        ) {
                            if (versioning.get(actor, id).checkedOutBy == null) versioning.checkOut(actor, id)
                            if (p.existing != null && row.attributes.isNotEmpty()) {
                                val current = versioning.workingCopy(actor, id).attributes
                                versioning.updateWorkingCopy(actor, id, current + row.attributes)
                            }
                            if (file != null) {
                                val name = file.first.substringAfterLast('/')
                                content.store(
                                    actor,
                                    id,
                                    name,
                                    URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream",
                                    file.second,
                                    ContentRole.PRIMARY,
                                )
                            }
                            versioning.checkIn(actor, id, "Bulk load from ${stored.sheet.name}")
                        }
                        p.state?.let { state ->
                            val current = versioning.get(actor, id)
                            if (!current.latest.state.equals(state, true)) {
                                versioning.setState(
                                    actor,
                                    id,
                                    SetState(state.uppercase(), "Bulk load", override = actor.siteAdmin),
                                )
                            }
                        }
                        id
                    }
                val obj = versioning.get(actor, id)
                ids[obj.number.lowercase()] = id.value
                row.number?.let { ids[it.lowercase()] = id.value }
                record(row.row, id.value, obj.number, true, if (p.existing == null) "Created" else "Updated")
            } catch (e: PlmException) {
                record(row.row, p.existing?.id?.value, row.number ?: row.name, false, e.message ?: e.code)
            }
        }

        // Then each part's structure and document links, in one new iteration per part.
        val partNumbers = (parents.keys + linkers.mapNotNull { it.view.number?.lowercase() }).toSet()
        for (number in partNumbers) {
            context.checkCancelled()
            val partId = ids[number] ?: planner.find(actor, number)?.id?.value
            if (partId == null) {
                record(null, null, number, false, "Not changed: it couldn't be created")
                continue
            }
            val children = parents[number].orEmpty()
            val docs = linkers.filter { it.view.number?.lowercase() == number }.flatMap { it.documents }
            try {
                val message =
                    tx.execute {
                        val id = MasterId(partId)
                        val mine = versioning.get(actor, id).checkedOutBy == actor.principal.value
                        if (!mine) versioning.checkOut(actor, id)
                        val lines = bom.usages(actor, id, workingCopy = true)
                        var added = 0
                        for (c in children) {
                            val childId =
                                ids[c.view.number!!.lowercase()]
                                    ?: throw ValidationException("INVALID_LOAD", "${c.view.number} wasn't created")
                            if (lines.any { it.child.id == childId }) continue
                            bom.add(actor, id, AddUsage(childId, c.quantity, findNumber = c.findNumber))
                            added++
                        }
                        val existingLinks = links.links(actor, id, workingCopy = true).map { it.document.id }.toSet()
                        var linked = 0
                        for (d in docs) {
                            val docId = ids[d.lowercase()] ?: planner.find(actor, d)?.id?.value ?: continue
                            if (docId in existingLinks) continue
                            links.link(actor, id, DocumentLinkKind.DESCRIBED_BY, docId)
                            linked++
                        }
                        versioning.checkIn(actor, id, "Bulk load from ${stored.sheet.name}")
                        listOfNotNull(
                            if (added > 0) "$added structure line${if (added == 1) "" else "s"} added" else null,
                            if (linked > 0) "$linked document${if (linked == 1) "" else "s"} linked" else null,
                        ).joinToString(", ").ifEmpty { "Structure and links already there" }
                    }
                record(null, partId, versioning.get(actor, MasterId(partId)).number, true, message)
            } catch (e: PlmException) {
                record(null, partId, number, false, e.message ?: e.code)
            }
        }
        files.delete(loadId)
        val failed = results.count { it["ok"] == false }
        return mapOf("results" to results, "succeeded" to results.size - failed, "failed" to failed)
    }

    companion object {
        const val TYPE = "bulk.load"
    }
}
