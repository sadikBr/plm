package com.plm.change.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Permission
import com.plm.change.api.AddChangeLink
import com.plm.change.api.ChangeFrom
import com.plm.change.api.ChangeKind
import com.plm.change.api.ChangeLink
import com.plm.change.api.ChangeLinked
import com.plm.change.api.ChangeRole
import com.plm.change.api.ChangeService
import com.plm.change.api.ChangeView
import com.plm.change.api.LinkedObject
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

internal data class LinkRow(
    val id: UUID,
    val changeId: UUID,
    val targetId: UUID,
    val role: ChangeRole,
    val version: String?,
    val note: String?,
    val createdAt: java.time.Instant,
    val createdBy: String,
)

/** Which kind of change object [typeId] is, by its lineage; null for anything else. */
internal fun changeKind(
    types: TypeService,
    actor: Actor,
    typeId: TypeId,
): ChangeKind? {
    val lineage = types.lineage(actor, typeId)
    return when {
        TypeId.PROBLEM_REPORT in lineage -> ChangeKind.PROBLEM_REPORT
        TypeId.CHANGE_REQUEST in lineage -> ChangeKind.CHANGE_REQUEST
        TypeId.CHANGE_NOTICE in lineage -> ChangeKind.CHANGE_NOTICE
        TypeId.CHANGE_TASK in lineage -> ChangeKind.CHANGE_TASK
        TypeId.VARIANCE in lineage -> ChangeKind.VARIANCE
        else -> null
    }
}

@Service
@Transactional
internal class DefaultChangeService(
    private val jdbc: JdbcClient,
    private val directory: com.plm.identity.api.PrincipalDirectory,
    private val personal: com.plm.notifications.api.PersonalService,
    @param:org.springframework.beans.factory.annotation.Value("\${plm.ui-url:http://localhost:5173}") private val uiUrl:
        String,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val access: AccessService,
    private val events: DomainEvents,
    private val clock: Clock,
) : ChangeService {
    private val linkMapper =
        org.springframework.jdbc.core.RowMapper { rs, _ ->
            LinkRow(
                rs.getObject("id", UUID::class.java),
                rs.getObject("change_id", UUID::class.java),
                rs.getObject("target_id", UUID::class.java),
                ChangeRole.valueOf(rs.getString("role")),
                rs.getString("version"),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"),
            )
        }

    internal fun rows(
        changeId: UUID? = null,
        targetId: UUID? = null,
    ): List<LinkRow> =
        jdbc
            .sql(
                """
                select * from change_link
                where (cast(:c as uuid) is null or change_id = :c) and (cast(:t as uuid) is null or target_id = :t)
                order by role, created_at
                """.trimIndent(),
            ).param("c", changeId)
            .param("t", targetId)
            .query(linkMapper)
            .list()

    private fun objects(
        actor: Actor,
        ids: Collection<UUID>,
    ): Map<UUID, VersionedObject> =
        ids
            .distinct()
            .chunked(500)
            .flatMap { versioning.list(actor, ObjectQuery(ids = it.map(::MasterId), limit = 500)) }
            .associateBy { it.id.value }

    private fun linked(
        id: UUID,
        o: VersionedObject?,
    ) = LinkedObject(id, o?.number, o?.name, o?.typeId?.value, o?.latest?.version, o?.latest?.state, o != null)

    @Transactional(readOnly = true)
    override fun kindOf(
        actor: Actor,
        objectId: UUID,
    ): ChangeKind? = changeKind(types, actor, versioning.get(actor, MasterId(objectId)).typeId)

    private fun change(
        actor: Actor,
        id: UUID,
    ): Pair<VersionedObject, ChangeKind> {
        val o = versioning.get(actor, MasterId(id))
        val kind =
            changeKind(types, actor, o.typeId)
                ?: throw NotFoundException("CHANGE_NOT_FOUND", "${o.number} isn't a change object")
        return o to kind
    }

    @Transactional(readOnly = true)
    override fun view(
        actor: Actor,
        changeId: UUID,
    ): ChangeView {
        val (o, kind) = change(actor, changeId)
        val out = rows(changeId = o.id.value)
        val incoming = rows(targetId = o.id.value).filter { it.role in CHAIN }
        val found = objects(actor, out.map { it.targetId } + incoming.map { it.changeId })
        val tasks = out.filter { it.role == ChangeRole.TASK }
        val plans = plans(tasks.map { it.targetId })
        val today = java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)
        return ChangeView(
            id = o.id.value,
            kind = kind,
            plan =
                tasks.map { t ->
                    val (assignee, due) = plans[t.targetId] ?: (null to null)
                    val task = linked(t.targetId, found[t.targetId])
                    com.plm.change.api.PlanEntry(
                        task,
                        assignee,
                        due,
                        due != null && due.isBefore(today) && task.state !in setOf("RESOLVED", "CANCELLED"),
                    )
                },
            links = out.map { it.view(found) },
            linkedFrom = incoming.map { it.view(found, from = true) },
        )
    }

    private fun LinkRow.view(
        found: Map<UUID, VersionedObject>,
        from: Boolean = false,
    ) = ChangeLink(
        id = id,
        changeId = changeId,
        role = role,
        target = linked(targetId, found[targetId]),
        change = if (from) linked(changeId, found[changeId]) else null,
        linkedVersion = version,
        note = note,
        createdAt = createdAt,
        createdBy = createdBy,
    )

    /** Linking needs MODIFY on the change, in a state where it may be edited. */
    private fun editable(
        actor: Actor,
        o: VersionedObject,
    ) {
        access.require(actor, AccessTarget(o.contextId, o.typeId, o.latest.state, o.id.value), Permission.MODIFY)
        if (!versioning.lifecycle(actor, o.id).editable) {
            throw ConflictException(
                "CHANGE_NOT_EDITABLE",
                "${o.number} is ${o.latest.state}: its links can't change now",
            )
        }
    }

    override fun link(
        actor: Actor,
        changeId: UUID,
        request: AddChangeLink,
    ): ChangeLink {
        val (o, kind) = change(actor, changeId)
        editable(actor, o)
        val target = versioning.get(actor, MasterId(request.targetId))
        val targetKind = changeKind(types, actor, target.typeId)
        val base = types.get(actor, target.typeId).baseClass

        fun bad(message: String): Nothing = throw ValidationException("INVALID_CHANGE_LINK", message)
        when (request.role) {
            ChangeRole.AFFECTED, ChangeRole.END_ITEM ->
                if (base ==
                    BaseClass.CHANGE
                ) {
                    bad("A change object can't be ${request.role.name.lowercase().replace('_', ' ')}")
                }
            ChangeRole.RESULTING -> {
                if (kind != ChangeKind.CHANGE_NOTICE &&
                    kind != ChangeKind.CHANGE_TASK
                ) {
                    bad("Only change notices and tasks have resulting objects")
                }
                if (base == BaseClass.CHANGE) bad("A change object can't be a resulting object")
            }
            ChangeRole.ADDRESSES ->
                if (kind != ChangeKind.CHANGE_REQUEST || targetKind != ChangeKind.PROBLEM_REPORT) {
                    bad("A change request addresses problem reports")
                }
            ChangeRole.IMPLEMENTS ->
                if (kind != ChangeKind.CHANGE_NOTICE || targetKind != ChangeKind.CHANGE_REQUEST) {
                    bad("A change notice implements change requests")
                }
            ChangeRole.TASK ->
                if (kind != ChangeKind.CHANGE_NOTICE ||
                    targetKind != ChangeKind.CHANGE_TASK
                ) {
                    bad("A change notice's tasks are change tasks")
                }
        }
        if (request.role == ChangeRole.END_ITEM && base != BaseClass.PART) bad("End items are parts")
        // Association rules (roadmap 5.10): when some apply, the target must be one of their types.
        val changeLineage = types.lineage(actor, o.typeId).map { it.value }
        val allowed =
            jdbc
                .sql(
                    """
                    select target_type_id from change_association_rule
                    where tenant_id = :tenant and role = :role and change_type_id in (:types)
                    """.trimIndent(),
                ).param("tenant", actor.tenant.value)
                .param("role", request.role.name)
                .param("types", changeLineage)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .list()
        if (allowed.isNotEmpty() && types.lineage(actor, target.typeId).none { it.value in allowed }) {
            val names = allowed.mapNotNull { runCatching { types.get(actor, TypeId(it)).displayName }.getOrNull() }
            bad("Only ${names.joinToString(", ")} can be ${request.role.name.lowercase().replace('_', ' ')} here")
        }
        if (target.id == o.id) bad("A change can't point to itself")
        val id = UuidV7.generate(clock)
        val inserted =
            jdbc
                .sql(
                    """
                    insert into change_link (id, tenant_id, change_id, target_id, role, version, note, created_at, created_by)
                    values (:id, :tenant, :change, :target, :role, :version, :note, :at, :by)
                    on conflict (change_id, target_id, role) do nothing
                    """.trimIndent(),
                ).param("id", id)
                .param("tenant", actor.tenant.value)
                .param("change", o.id.value)
                .param("target", target.id.value)
                .param("role", request.role.name)
                .param("version", target.latest.version)
                .param("note", request.note?.trim()?.ifEmpty { null })
                .param("at", Timestamp.from(clock.instant()))
                .param("by", actor.principal.value)
                .update()
        if (inserted == 0) throw ConflictException("ALREADY_LINKED", "${target.number} is already linked that way")
        events.publish(actor, ChangeLinked(o.id.value, target.id.value, request.role, true))
        return view(actor, changeId).links.first { it.id == id }
    }

    private fun plans(taskIds: List<UUID>): Map<UUID, Pair<String?, java.time.LocalDate?>> =
        if (taskIds.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql("select * from change_plan where task_id in (:ids)")
                .param("ids", taskIds)
                .query { rs, _ ->
                    rs.getObject("task_id", UUID::class.java) to
                        (rs.getString("assignee") to rs.getObject("due_date", java.time.LocalDate::class.java))
                }.list()
                .toMap()
        }

    override fun plan(
        actor: Actor,
        noticeId: UUID,
        taskId: UUID,
        request: com.plm.change.api.SetPlan,
    ): com.plm.change.api.PlanEntry {
        val (notice, kind) = change(actor, noticeId)
        if (kind !=
            ChangeKind.CHANGE_NOTICE
        ) {
            throw ValidationException("INVALID_PLAN", "Only change notices have a plan")
        }
        access.require(
            actor,
            AccessTarget(notice.contextId, notice.typeId, notice.latest.state, notice.id.value),
            Permission.MODIFY,
        )
        if (rows(changeId = noticeId).none { it.role == ChangeRole.TASK && it.targetId == taskId }) {
            throw NotFoundException("CHANGE_TASK_NOT_FOUND", "That isn't one of its tasks")
        }
        val assignee =
            request.assignee?.trim()?.ifEmpty { null }?.let {
                directory.findByUsername(actor, it)?.id?.value
                    ?: throw NotFoundException("PRINCIPAL_NOT_FOUND", "There's no user $it")
            }
        val before = plans(listOf(taskId))[taskId]?.first
        jdbc
            .sql(
                """
                insert into change_plan (task_id, tenant_id, assignee, due_date, updated_at, updated_by)
                values (:task, :tenant, :assignee, :due, :at, :by)
                on conflict (task_id) do update set assignee = excluded.assignee, due_date = excluded.due_date,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("task", taskId)
            .param("tenant", actor.tenant.value)
            .param("assignee", assignee)
            .param("due", request.dueDate)
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        if (assignee != null && assignee != before) {
            val task = versioning.get(actor, MasterId(taskId))
            personal.notify(
                actor.tenant,
                assignee,
                "Change task for you: ${task.number} ${task.name}",
                request.dueDate?.let { "Due $it, for ${notice.number}" } ?: "For ${notice.number}",
                "$uiUrl/objects/$taskId?tab=change",
                taskId,
                actor.principal.value,
            )
        }
        return view(actor, noticeId).plan.first { it.task.id == taskId }
    }

    override fun unlink(
        actor: Actor,
        changeId: UUID,
        linkId: UUID,
    ) {
        val (o, _) = change(actor, changeId)
        editable(actor, o)
        val row =
            rows(changeId = o.id.value).firstOrNull { it.id == linkId }
                ?: throw NotFoundException("CHANGE_LINK_NOT_FOUND", "No such link")
        jdbc.sql("delete from change_link where id = :id").param("id", linkId).update()
        events.publish(actor, ChangeLinked(o.id.value, row.targetId, row.role, false))
    }

    @Transactional(readOnly = true)
    override fun changesOf(
        actor: Actor,
        objectId: UUID,
    ): List<ChangeLink> {
        versioning.get(actor, MasterId(objectId))
        val incoming = rows(targetId = objectId).filter { it.role !in CHAIN }
        val found = objects(actor, incoming.map { it.changeId } + objectId)
        // Only the changes the reader can see.
        return incoming.filter { it.changeId in found }.map { it.view(found, from = true) }
    }

    override fun createFrom(
        actor: Actor,
        request: ChangeFrom,
    ): UUID {
        val (from, fromKind) = change(actor, request.fromId)
        val (type, role) =
            when (request.kind) {
                ChangeKind.CHANGE_REQUEST -> {
                    if (fromKind !=
                        ChangeKind.PROBLEM_REPORT
                    ) {
                        throw ValidationException("INVALID_CHANGE", "Change requests come from problem reports")
                    }
                    TypeId.CHANGE_REQUEST to ChangeRole.ADDRESSES
                }
                ChangeKind.CHANGE_NOTICE -> {
                    if (fromKind != ChangeKind.CHANGE_REQUEST && fromKind != ChangeKind.PROBLEM_REPORT) {
                        throw ValidationException(
                            "INVALID_CHANGE",
                            "Change notices come from change requests (or, fast track, problem reports)",
                        )
                    }
                    TypeId.CHANGE_NOTICE to if (fromKind == ChangeKind.CHANGE_REQUEST) ChangeRole.IMPLEMENTS else null
                }
                ChangeKind.CHANGE_TASK -> {
                    if (fromKind !=
                        ChangeKind.CHANGE_NOTICE
                    ) {
                        throw ValidationException("INVALID_CHANGE", "Change tasks belong to change notices")
                    }
                    TypeId.CHANGE_TASK to null
                }
                ChangeKind.PROBLEM_REPORT, ChangeKind.VARIANCE -> throw ValidationException(
                    "INVALID_CHANGE",
                    "Problem reports and variances start on their own",
                )
            }
        val chosenType = request.typeId?.let(::TypeId)?.takeIf { type in types.lineage(actor, it) } ?: type
        val created =
            versioning
                .create(
                    actor,
                    CreateVersionedObject(
                        chosenType,
                        from.contextId,
                        request.name.trim().ifEmpty {
                            from.name
                        },
                    ),
                )
        val inherited =
            rows(changeId = from.id.value).filter {
                it.role == ChangeRole.AFFECTED ||
                    it.role == ChangeRole.END_ITEM
            }
        val take =
            if (request.kind == ChangeKind.CHANGE_TASK && request.affected != null) {
                inherited.filter { it.role == ChangeRole.AFFECTED && it.targetId in request.affected }
            } else {
                inherited
            }

        fun insert(
            change: UUID,
            target: UUID,
            r: ChangeRole,
            version: String?,
        ) {
            jdbc
                .sql(
                    """
                    insert into change_link (id, tenant_id, change_id, target_id, role, version, created_at, created_by)
                    values (:id, :tenant, :change, :target, :role, :version, :at, :by)
                    on conflict (change_id, target_id, role) do nothing
                    """.trimIndent(),
                ).param("id", UuidV7.generate(clock))
                .param("tenant", actor.tenant.value)
                .param("change", change)
                .param("target", target)
                .param("role", r.name)
                .param("version", version)
                .param("at", Timestamp.from(clock.instant()))
                .param("by", actor.principal.value)
                .update()
        }
        take.forEach { insert(created.id.value, it.targetId, it.role, it.version) }
        when {
            request.kind == ChangeKind.CHANGE_TASK ->
                insert(
                    from.id.value,
                    created.id.value,
                    ChangeRole.TASK,
                    created.latest.version,
                )
            role != null -> insert(created.id.value, from.id.value, role, from.latest.version)
        }
        events.publish(actor, ChangeLinked(created.id.value, from.id.value, role ?: ChangeRole.TASK, true))
        return created.id.value
    }

    companion object {
        val CHAIN = setOf(ChangeRole.ADDRESSES, ChangeRole.IMPLEMENTS, ChangeRole.TASK)
    }
}
