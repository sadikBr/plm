package com.plm.workspace.domain

import com.plm.cad.api.CadService
import com.plm.cad.api.CollectOptions
import com.plm.cad.api.CollectorService
import com.plm.content.api.AttachContent
import com.plm.content.api.ContentItem
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import com.plm.workspace.api.AddNewFile
import com.plm.workspace.api.AddToWorkspace
import com.plm.workspace.api.CreateFromFile
import com.plm.workspace.api.CreateWorkspace
import com.plm.workspace.api.Download
import com.plm.workspace.api.FileState
import com.plm.workspace.api.NewFile
import com.plm.workspace.api.OperationResult
import com.plm.workspace.api.ReportLocal
import com.plm.workspace.api.UploadChange
import com.plm.workspace.api.Workspace
import com.plm.workspace.api.WorkspaceDetail
import com.plm.workspace.api.WorkspaceEvent
import com.plm.workspace.api.WorkspaceFile
import com.plm.workspace.api.WorkspaceItem
import com.plm.workspace.api.WorkspaceOperation
import com.plm.workspace.api.WorkspaceService
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.OutputStream
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val MAX_ITEMS = 2000

private data class Row(
    val objectId: UUID,
    val baseIteration: UUID?,
    val localSha: String?,
    val addedAt: Instant,
)

@Service
@Transactional
internal class DefaultWorkspaceService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val content: ContentService,
    private val cad: CadService,
    private val collector: CollectorService,
    private val clock: Clock,
) : WorkspaceService {
    private val mapper =
        RowMapper { rs, _ ->
            Workspace(
                rs.getObject("id", UUID::class.java),
                rs.getString("name"),
                rs.getObject("context_id", UUID::class.java),
                rs.getObject("folder_id", UUID::class.java),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
            )
        }

    // ---------- Workspaces ----------

    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<Workspace> =
        jdbc
            .sql("select * from workspace where tenant_id = :t and owner = :o order by name")
            .param("t", actor.tenant.value)
            .param("o", actor.principal.value)
            .query(mapper)
            .list()
            .map { ws -> ws.copy(counts = items(actor, ws.id).groupingBy { it.fileState }.eachCount()) }

    override fun create(
        actor: Actor,
        command: CreateWorkspace,
    ): Workspace {
        val id = UuidV7.generate(clock)
        val now = Timestamp.from(clock.instant())
        try {
            jdbc
                .sql(
                    """insert into workspace (id, tenant_id, owner, name, context_id, folder_id, description, created_at, updated_at)
                       values (:id, :t, :o, :n, :c, :f, :d, :now, :now)""",
                ).param("id", id)
                .param("t", actor.tenant.value)
                .param("o", actor.principal.value)
                .param("n", name(command.name))
                .param("c", command.contextId)
                .param("f", command.folderId)
                .param("d", command.description?.trim()?.ifEmpty { null })
                .param("now", now)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_WORKSPACE",
                "You already have a workspace named “${command.name.trim()}”",
            )
        }
        log(id, "CREATED", null, null, command.name.trim())
        return find(actor, id)
    }

    override fun update(
        actor: Actor,
        id: UUID,
        command: CreateWorkspace,
    ): Workspace {
        find(actor, id)
        try {
            jdbc
                .sql(
                    """update workspace set name = :n, context_id = :c, folder_id = :f, description = :d, updated_at = :now
                       where id = :id""",
                ).param("id", id)
                .param("n", name(command.name))
                .param("c", command.contextId)
                .param("f", command.folderId)
                .param("d", command.description?.trim()?.ifEmpty { null })
                .param("now", Timestamp.from(clock.instant()))
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_WORKSPACE",
                "You already have a workspace named “${command.name.trim()}”",
            )
        }
        return find(actor, id)
    }

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        find(actor, id)
        jdbc.sql("delete from workspace where id = :id").param("id", id).update()
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: UUID,
    ): WorkspaceDetail = WorkspaceDetail(find(actor, id), items(actor, id), newFiles(id))

    // ---------- Items ----------

    override fun add(
        actor: Actor,
        id: UUID,
        command: AddToWorkspace,
    ): WorkspaceDetail {
        find(actor, id)
        val ids = linkedSetOf<UUID>()
        for (objectId in command.ids) {
            versioning.get(actor, MasterId(objectId))
            ids += objectId
            if (command.dependencies && cad.kindOf(actor, objectId) != null) {
                collector
                    .collect(actor, objectId, CollectOptions("REQUIRED", drawings = true))
                    .filter { it.model.readable }
                    .forEach { ids += it.model.id }
            }
        }
        val present = rows(id).map { it.objectId }.toSet()
        if (present.size + ids.count { it !in present } > MAX_ITEMS) {
            throw ValidationException("WORKSPACE_FULL", "A workspace holds at most $MAX_ITEMS objects")
        }
        for (objectId in ids - present) {
            val obj = versioning.get(actor, MasterId(objectId))
            jdbc
                .sql(
                    """insert into workspace_item (workspace_id, object_id, base_iteration, added_at)
                       values (:w, :o, :b, :now) on conflict do nothing""",
                ).param("w", id)
                .param("o", objectId)
                .param("b", obj.latest.id.value)
                .param("now", Timestamp.from(clock.instant()))
                .update()
            log(id, "ADDED", objectId, obj.number, null)
        }
        touch(id)
        return get(actor, id)
    }

    override fun remove(
        actor: Actor,
        id: UUID,
        ids: List<UUID>,
    ): WorkspaceDetail {
        find(actor, id)
        for (objectId in ids) {
            val n =
                jdbc
                    .sql("delete from workspace_item where workspace_id = :w and object_id = :o")
                    .param("w", id)
                    .param("o", objectId)
                    .update()
            if (n > 0) log(id, "REMOVED", objectId, numberOf(actor, objectId), null)
        }
        touch(id)
        return get(actor, id)
    }

    override fun reportLocal(
        actor: Actor,
        id: UUID,
        report: ReportLocal,
    ): WorkspaceDetail {
        find(actor, id)
        val now = Timestamp.from(clock.instant())
        for (f in report.files) {
            jdbc
                .sql(
                    """update workspace_item set base_iteration = coalesce(:b, base_iteration), local_sha256 = :s, local_seen_at = :now
                       where workspace_id = :w and object_id = :o""",
                ).param("w", id)
                .param("o", f.objectId)
                .param("b", f.iterationId)
                .param("s", f.sha256?.lowercase())
                .param("now", now)
                .update()
        }
        return get(actor, id)
    }

    @Transactional(readOnly = true)
    override fun downloads(
        actor: Actor,
        id: UUID,
        ids: List<UUID>,
    ): List<Download> {
        find(actor, id)
        return items(actor, id)
            .filter { if (ids.isEmpty()) it.fileState == FileState.OUT_OF_DATE else it.objectId in ids }
            .mapNotNull { item ->
                val obj = versioning.get(actor, MasterId(item.objectId))
                val mine = obj.checkedOutBy == actor.principal.value
                val file = (if (mine) item.workingFile else null) ?: item.file ?: return@mapNotNull null
                val iteration = if (mine) versioning.workingCopy(actor, obj.id) else obj.latest
                Download(
                    item.objectId,
                    item.number,
                    iteration.id.value,
                    iteration.version,
                    file.fileName,
                    file.sha256,
                    content.download(actor, file.contentId).url,
                )
            }
    }

    // ---------- Operations (all or nothing) ----------

    override fun checkOut(
        actor: Actor,
        id: UUID,
        op: WorkspaceOperation,
    ): List<OperationResult> {
        find(actor, id)
        val targets = chosen(actor, id, op.ids) { it.checkedOutBy == null }
        return targets
            .map { obj ->
                if (obj.checkedOutBy ==
                    actor.principal.value
                ) {
                    return@map OperationResult(obj.id.value, obj.number, obj.latest.version, false)
                }
                val wc = named(obj) { versioning.checkOut(actor, obj.id) }
                log(id, "CHECKED_OUT", obj.id.value, obj.number, wc.version)
                OperationResult(obj.id.value, obj.number, wc.version, true)
            }.also { touch(id) }
    }

    override fun checkIn(
        actor: Actor,
        id: UUID,
        op: WorkspaceOperation,
    ): List<OperationResult> {
        find(actor, id)
        val targets = chosen(actor, id, op.ids) { it.checkedOutBy == actor.principal.value }
        if (targets.isEmpty()) throw ValidationException("NOTHING_TO_CHECK_IN", "Nothing here is checked out to you")
        return targets
            .map { obj ->
                if (obj.checkedOutBy != actor.principal.value) {
                    throw ConflictException("NOT_CHECKED_OUT", "${obj.number}: it isn't checked out to you")
                }
                val done = named(obj) { versioning.checkIn(actor, obj.id, op.comment?.trim()?.ifEmpty { null }) }
                // Their computer holds what they uploaded: the new iteration.
                val sha = primary(actor, done.id, done.latest.id.value)?.sha256
                jdbc
                    .sql(
                        "update workspace_item set base_iteration = :b, local_sha256 = :s where workspace_id = :w and object_id = :o",
                    ).param("w", id)
                    .param("o", obj.id.value)
                    .param("b", done.latest.id.value)
                    .param("s", sha)
                    .update()
                log(id, "CHECKED_IN", obj.id.value, obj.number, done.latest.version)
                OperationResult(obj.id.value, obj.number, done.latest.version, true)
            }.also { touch(id) }
    }

    override fun undo(
        actor: Actor,
        id: UUID,
        op: WorkspaceOperation,
    ): List<OperationResult> {
        find(actor, id)
        val targets = chosen(actor, id, op.ids) { it.checkedOutBy == actor.principal.value }
        return targets
            .map { obj ->
                if (obj.checkedOutBy !=
                    actor.principal.value
                ) {
                    return@map OperationResult(obj.id.value, obj.number, obj.latest.version, false)
                }
                named(obj) { versioning.undoCheckOut(actor, obj.id) }
                log(id, "UNDONE", obj.id.value, obj.number, null)
                OperationResult(obj.id.value, obj.number, obj.latest.version, true)
            }.also { touch(id) }
    }

    override fun upload(
        actor: Actor,
        id: UUID,
        change: UploadChange,
    ): WorkspaceItem {
        find(actor, id)
        val row = rows(id).firstOrNull { it.objectId == change.objectId } ?: notInWorkspace()
        var obj = versioning.get(actor, MasterId(change.objectId))
        if (obj.checkedOutBy == null) {
            // A change to an out-of-date file would lose what others checked in meanwhile.
            if (row.baseIteration != null && row.baseIteration != obj.latest.id.value) {
                throw ConflictException(
                    "OUT_OF_DATE",
                    "${obj.number} changed since you took it (now ${obj.latest.version}): update it, then make your change again",
                )
            }
            named(obj) { versioning.checkOut(actor, obj.id) }
            log(id, "CHECKED_OUT", obj.id.value, obj.number, null)
            obj = versioning.get(actor, obj.id)
        } else if (obj.checkedOutBy != actor.principal.value) {
            throw ConflictException("CHECKED_OUT_BY_ANOTHER_USER", "${obj.number} is checked out by someone else")
        }
        val item = named(obj) { content.attach(actor, obj.id, AttachContent(change.uploadId, ContentRole.PRIMARY)) }
        jdbc
            .sql(
                "update workspace_item set local_sha256 = :s, local_seen_at = :now where workspace_id = :w and object_id = :o",
            ).param("w", id)
            .param("o", obj.id.value)
            .param("s", item.sha256)
            .param("now", Timestamp.from(clock.instant()))
            .update()
        log(id, "UPLOADED", obj.id.value, obj.number, item.fileName)
        touch(id)
        return items(actor, id).first { it.objectId == obj.id.value }
    }

    // ---------- New files ----------

    override fun addNewFile(
        actor: Actor,
        id: UUID,
        file: AddNewFile,
    ): NewFile {
        find(actor, id)
        val fileName =
            file.fileName
                .trim()
                .substringAfterLast('/')
                .substringAfterLast('\\')
        if (fileName.isEmpty() ||
            fileName.length > 255
        ) {
            throw ValidationException("INVALID_FILE_NAME", "Give the file a name")
        }
        val fid = UuidV7.generate(clock)
        jdbc
            .sql(
                """insert into workspace_file (id, workspace_id, file_name, upload_id, size, added_at)
                   values (:id, :w, :n, :u, :s, :now)
                   on conflict (workspace_id, file_name) do update set upload_id = excluded.upload_id, size = excluded.size,
                     added_at = excluded.added_at""",
            ).param("id", fid)
            .param("w", id)
            .param("n", fileName)
            .param("u", file.uploadId)
            .param("s", file.size)
            .param("now", Timestamp.from(clock.instant()))
            .update()
        touch(id)
        return newFiles(id).first { it.fileName == fileName }
    }

    override fun removeNewFile(
        actor: Actor,
        id: UUID,
        fileId: UUID,
    ) {
        find(actor, id)
        jdbc
            .sql("delete from workspace_file where workspace_id = :w and id = :f")
            .param("w", id)
            .param("f", fileId)
            .update()
    }

    override fun createFromFile(
        actor: Actor,
        id: UUID,
        fileId: UUID,
        command: CreateFromFile,
    ): WorkspaceItem {
        val ws = find(actor, id)
        val file =
            jdbc
                .sql("select file_name, upload_id from workspace_file where workspace_id = :w and id = :f")
                .param("w", id)
                .param("f", fileId)
                .query { rs, _ -> rs.getString("file_name") to rs.getObject("upload_id", UUID::class.java) }
                .optional()
                .orElseThrow { NotFoundException("FILE_NOT_FOUND", "There's no such new file in this workspace") }
        val created =
            versioning.create(
                actor,
                CreateVersionedObject(
                    TypeId(command.typeId),
                    com.plm.access.api
                        .ContextId(ws.contextId),
                    command.name.trim().ifEmpty { file.first.substringBeforeLast('.') },
                    command.number?.trim()?.ifEmpty { null },
                    folderId = ws.folderId,
                ),
            )
        versioning.checkOut(actor, created.id)
        val item = content.attach(actor, created.id, AttachContent(file.second, ContentRole.PRIMARY))
        val done = versioning.checkIn(actor, created.id, "From workspace ${ws.name}")
        jdbc.sql("delete from workspace_file where id = :f").param("f", fileId).update()
        jdbc
            .sql(
                """insert into workspace_item (workspace_id, object_id, base_iteration, local_sha256, added_at)
                   values (:w, :o, :b, :s, :now)""",
            ).param("w", id)
            .param("o", created.id.value)
            .param("b", done.latest.id.value)
            .param("s", item.sha256)
            .param("now", Timestamp.from(clock.instant()))
            .update()
        log(id, "CREATED_OBJECT", created.id.value, created.number, file.first)
        touch(id)
        return items(actor, id).first { it.objectId == created.id.value }
    }

    @Transactional(readOnly = true)
    override fun history(
        actor: Actor,
        id: UUID,
    ): List<WorkspaceEvent> {
        find(actor, id)
        return jdbc
            .sql("select * from workspace_event where workspace_id = :w order by at desc, id desc limit 200")
            .param("w", id)
            .query { rs, _ ->
                WorkspaceEvent(
                    rs.getTimestamp("at").toInstant(),
                    rs.getString("kind"),
                    rs.getObject("object_id", UUID::class.java),
                    rs.getString("number"),
                    rs.getString("detail"),
                )
            }.list()
    }

    @Transactional(readOnly = true)
    override fun zip(
        actor: Actor,
        id: UUID,
        out: OutputStream,
    ) {
        find(actor, id)
        val used = mutableSetOf<String>()
        ZipOutputStream(out).use { zip ->
            for (item in items(actor, id)) {
                val file = item.workingFile ?: item.file ?: continue
                var name = file.fileName
                if (!used.add(name.lowercase())) {
                    name = "${item.number}-${file.fileName}"
                    used += name.lowercase()
                }
                zip.putNextEntry(ZipEntry(name))
                content.open(actor, file.contentId).use { it.transferTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // ---------- State ----------

    private fun items(
        actor: Actor,
        id: UUID,
    ): List<WorkspaceItem> = rows(id).map { item(actor, it) }

    private fun item(
        actor: Actor,
        row: Row,
    ): WorkspaceItem {
        val obj =
            try {
                versioning.get(actor, MasterId(row.objectId))
            } catch (e: NotFoundException) {
                null
            }
        if (obj == null) {
            return WorkspaceItem(
                row.objectId,
                "—",
                "",
                null,
                null,
                null,
                FileState.UNAVAILABLE,
                null,
                null,
                null,
                null,
                null,
                row.addedAt,
            )
        }
        val me = actor.principal.value
        val latest = primary(actor, obj.id, obj.latest.id.value)
        val working = if (obj.checkedOutBy == me) workingPrimary(actor, obj.id) else null
        val base: Iteration? =
            if (row.baseIteration == null || row.baseIteration == obj.latest.id.value) {
                obj.latest
            } else {
                versioning.history(actor, obj.id).firstOrNull { it.id.value == row.baseIteration }
            }
        val baseSha =
            if (base?.id == obj.latest.id) {
                latest?.sha256
            } else {
                base?.let { primary(actor, obj.id, it.id.value)?.sha256 }
            }
        val local = row.localSha
        val state =
            when {
                obj.checkedOutBy != null && obj.checkedOutBy != me -> FileState.CHECKED_OUT_BY_OTHER
                obj.checkedOutBy == me ->
                    if (local != null && local != working?.sha256) FileState.MODIFIED else FileState.CHECKED_OUT
                else -> {
                    val outOfDate = row.baseIteration != null && row.baseIteration != obj.latest.id.value
                    val modified = local != null && local != baseSha
                    when {
                        outOfDate && modified -> FileState.CONFLICT
                        outOfDate -> FileState.OUT_OF_DATE
                        modified -> FileState.MODIFIED
                        else -> FileState.IN_SYNC
                    }
                }
            }
        return WorkspaceItem(
            obj.id.value,
            obj.number,
            obj.name,
            obj.typeId.value,
            obj.latest.version,
            obj.latest.state,
            state,
            latest?.let(::file),
            working?.let(::file),
            base?.version,
            obj.checkedOutBy,
            cad.kindOf(actor, obj.id.value)?.name,
            row.addedAt,
        )
    }

    private fun file(c: ContentItem) = WorkspaceFile(c.id, c.fileName, c.size, c.sha256)

    private fun primary(
        actor: Actor,
        id: MasterId,
        iteration: UUID,
    ): ContentItem? =
        try {
            content.list(actor, id, iteration).firstOrNull { it.role == ContentRole.PRIMARY }
        } catch (e: PlmException) {
            null
        }

    private fun workingPrimary(
        actor: Actor,
        id: MasterId,
    ): ContentItem? =
        try {
            content.list(actor, id, workingCopy = true).firstOrNull { it.role == ContentRole.PRIMARY }
        } catch (e: PlmException) {
            null
        }

    // ---------- Helpers ----------

    private fun find(
        actor: Actor,
        id: UUID,
    ): Workspace =
        jdbc
            .sql("select * from workspace where tenant_id = :t and id = :id and owner = :o")
            .param("t", actor.tenant.value)
            .param("id", id)
            .param("o", actor.principal.value)
            .query(mapper)
            .optional()
            .orElseThrow { NotFoundException("WORKSPACE_NOT_FOUND", "Workspace $id does not exist") }

    private fun rows(id: UUID): List<Row> =
        jdbc
            .sql("select * from workspace_item where workspace_id = :w order by added_at, object_id")
            .param("w", id)
            .query { rs, _ ->
                Row(
                    rs.getObject("object_id", UUID::class.java),
                    rs.getObject("base_iteration", UUID::class.java),
                    rs.getString("local_sha256"),
                    rs.getTimestamp("added_at").toInstant(),
                )
            }.list()

    private fun newFiles(id: UUID): List<NewFile> =
        jdbc
            .sql("select * from workspace_file where workspace_id = :w order by file_name")
            .param("w", id)
            .query { rs, _ ->
                NewFile(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("file_name"),
                    rs.getObject("size") as Long?,
                    rs.getTimestamp("added_at").toInstant(),
                )
            }.list()

    /** The items an operation applies to: the chosen ones, or every one that [default] picks. */
    private fun chosen(
        actor: Actor,
        id: UUID,
        ids: List<UUID>,
        default: (VersionedObject) -> Boolean,
    ): List<VersionedObject> {
        val present = rows(id).map { it.objectId }
        if (ids.isNotEmpty()) {
            ids.firstOrNull { it !in present }?.let { notInWorkspace() }
            return ids.map { versioning.get(actor, MasterId(it)) }
        }
        return present
            .mapNotNull {
                try {
                    versioning.get(actor, MasterId(it))
                } catch (e: NotFoundException) {
                    null
                }
            }.filter(default)
    }

    /** Runs [block], naming the object in its error, so a failed batch says which one failed. */
    private fun <T> named(
        obj: VersionedObject,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: PlmException) {
            // Most messages already name the object; the others get its number in front.
            val message = if (e.message?.contains(obj.number) == true) e.message!! else "${obj.number}: ${e.message}"
            throw when (e) {
                is NotFoundException -> NotFoundException(e.code, message, e.details)
                is ConflictException -> ConflictException(e.code, message, e.details)
                is ValidationException -> ValidationException(e.code, message, e.details)
                is ForbiddenException -> ForbiddenException(e.code, message, e.details)
            }
        }

    private fun numberOf(
        actor: Actor,
        objectId: UUID,
    ): String? =
        try {
            versioning.get(actor, MasterId(objectId)).number
        } catch (e: NotFoundException) {
            null
        }

    private fun notInWorkspace(): Nothing =
        throw NotFoundException("NOT_IN_WORKSPACE", "That object isn't in this workspace")

    private fun name(text: String): String {
        val n = text.trim()
        if (n.isEmpty() ||
            n.length > 100
        ) {
            throw ValidationException("INVALID_NAME", "Give the workspace a name of up to 100 characters")
        }
        return n
    }

    private fun touch(id: UUID) {
        jdbc
            .sql("update workspace set updated_at = :now where id = :id")
            .param("id", id)
            .param("now", Timestamp.from(clock.instant()))
            .update()
    }

    private fun log(
        workspace: UUID,
        kind: String,
        objectId: UUID?,
        number: String?,
        detail: String?,
    ) {
        jdbc
            .sql(
                """insert into workspace_event (id, workspace_id, at, kind, object_id, number, detail)
                   values (:id, :w, :at, :k, :o, :n, :d)""",
            ).param("id", UuidV7.generate(clock))
            .param("w", workspace)
            .param("at", Timestamp.from(clock.instant()))
            .param("k", kind)
            .param("o", objectId)
            .param("n", number)
            .param("d", detail)
            .update()
    }
}
