package com.plm.change.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Permission
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.UpdateUsage
import com.plm.change.api.AddRedline
import com.plm.change.api.ChangeKind
import com.plm.change.api.ChangeRole
import com.plm.change.api.LinkedObject
import com.plm.change.api.MassChange
import com.plm.change.api.MassChangeResult
import com.plm.change.api.MassChangeSkip
import com.plm.change.api.Redline
import com.plm.change.api.RedlineOp
import com.plm.change.api.RedlineService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.types.api.AttributeService
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private val log = LoggerFactory.getLogger(DefaultRedlineService::class.java)

private val FINISHED = setOf("RESOLVED", "CANCELLED", "REJECTED")

private data class RedlineRow(
    val id: UUID,
    val changeId: UUID,
    val op: RedlineOp,
    val targetId: UUID,
    val childId: UUID?,
    val newChildId: UUID?,
    val quantity: BigDecimal?,
    val attribute: String?,
    val value: Any?,
    val note: String?,
    val createdAt: java.time.Instant,
    val createdBy: String,
    val appliedAt: java.time.Instant?,
    val appliedVersion: String?,
)

@Service
@Transactional
internal class DefaultRedlineService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val attributes: AttributeService,
    private val bom: BomService,
    private val access: AccessService,
    private val changes: DefaultChangeService,
    private val clock: Clock,
) : RedlineService {
    private val json = JsonMapper.builder().build()

    private fun rows(changeId: UUID): List<RedlineRow> =
        jdbc
            .sql("select * from change_redline where change_id = :c order by created_at, id")
            .param("c", changeId)
            .query { rs, _ ->
                RedlineRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("change_id", UUID::class.java),
                    RedlineOp.valueOf(rs.getString("op")),
                    rs.getObject("target_id", UUID::class.java),
                    rs.getObject("child_id", UUID::class.java),
                    rs.getObject("new_child_id", UUID::class.java),
                    rs.getBigDecimal("quantity"),
                    rs.getString("attribute"),
                    rs.getString("value")?.let { json.readValue(it, Any::class.java) },
                    rs.getString("note"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("created_by"),
                    rs.getTimestamp("applied_at")?.toInstant(),
                    rs.getString("applied_version"),
                )
            }.list()

    private fun find(
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

    private fun views(
        actor: Actor,
        rows: List<RedlineRow>,
    ): List<Redline> {
        val objects = find(actor, rows.flatMap { listOfNotNull(it.targetId, it.childId, it.newChildId) })
        return rows.map { r ->
            Redline(
                r.id,
                r.changeId,
                r.op,
                linked(r.targetId, objects[r.targetId]),
                r.childId?.let { linked(it, objects[it]) },
                r.newChildId?.let { linked(it, objects[it]) },
                r.quantity,
                r.attribute,
                r.value,
                r.note,
                r.createdAt,
                r.createdBy,
                r.appliedAt,
                r.appliedVersion,
            )
        }
    }

    /** The change, if [actor] may propose edits on it: a notice or task, not finished. */
    private fun editableChange(
        actor: Actor,
        changeId: UUID,
    ): VersionedObject {
        val o = versioning.get(actor, MasterId(changeId))
        val kind = changeKind(types, actor, o.typeId)
        if (kind != ChangeKind.CHANGE_NOTICE && kind != ChangeKind.CHANGE_TASK) {
            throw ValidationException("INVALID_REDLINE", "Only change notices and tasks propose edits")
        }
        access.require(actor, AccessTarget(o.contextId, o.typeId, o.latest.state, o.id.value), Permission.MODIFY)
        if (o.latest.state in FINISHED) {
            throw ConflictException(
                "CHANGE_NOT_EDITABLE",
                "${o.number} is ${o.latest.state}: its edits can't change now",
            )
        }
        return o
    }

    private fun isPart(
        actor: Actor,
        o: VersionedObject,
    ) = TypeId.PART in types.lineage(actor, o.typeId)

    /** Why [r] can't be proposed on [target]; null when it can. Never throws for expected cases. */
    private fun problem(
        actor: Actor,
        target: VersionedObject,
        r: AddRedline,
    ): String? {
        if (r.op == RedlineOp.SET_ATTRIBUTE) {
            val name = r.attribute?.trim()
            if (name.isNullOrEmpty()) return "Which attribute?"
            if (attributes.effective(actor, target.typeId).none { it.name == name }) {
                return "${target.number} has no attribute $name"
            }
            return null
        }
        if (!isPart(actor, target)) return "${target.number} is not a part: it has no structure"
        val childId = r.childId ?: return "Which part?"
        val objects = find(actor, listOfNotNull(childId, r.newChildId))
        val child = objects[childId] ?: return "The part to use isn't found"
        if (!isPart(actor, child)) return "${child.number} is not a part"
        if (r.quantity != null && r.quantity.signum() <= 0) return "The quantity must be positive"
        if (r.op == RedlineOp.ADD) return if (child.id == target.id) "A part can't use itself" else null
        if (bom
                .usages(
                    actor,
                    target.id,
                ).none { it.child.id == childId }
        ) {
            return "${target.number} doesn't use ${child.number}"
        }
        return when (r.op) {
            RedlineOp.QUANTITY -> if (r.quantity == null) "Which quantity?" else null
            RedlineOp.REPLACE -> {
                val replacement = r.newChildId?.let { objects[it] } ?: return "Replace with which part?"
                when {
                    !isPart(actor, replacement) -> "${replacement.number} is not a part"
                    replacement.id == child.id -> "That's the same part"
                    replacement.id == target.id -> "A part can't use itself"
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun insert(
        actor: Actor,
        changeId: UUID,
        r: AddRedline,
    ): UUID {
        val id = UuidV7.generate(clock)
        jdbc
            .sql(
                """
                insert into change_redline
                    (id, tenant_id, change_id, op, target_id, child_id, new_child_id, quantity, attribute, value, note,
                     created_at, created_by)
                values (:id, :t, :c, :op, :target, :child, :newChild, :q, :attr, cast(:value as jsonb), :note, :at, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("t", actor.tenant.value)
            .param("c", changeId)
            .param("op", r.op.name)
            .param("target", r.targetId)
            .param("child", if (r.op == RedlineOp.SET_ATTRIBUTE) null else r.childId)
            .param("newChild", if (r.op == RedlineOp.REPLACE) r.newChildId else null)
            .param("q", if (r.op == RedlineOp.ADD || r.op == RedlineOp.QUANTITY) r.quantity ?: BigDecimal.ONE else null)
            .param("attr", if (r.op == RedlineOp.SET_ATTRIBUTE) r.attribute?.trim() else null)
            .param("value", if (r.op == RedlineOp.SET_ATTRIBUTE) json.writeValueAsString(r.value) else null)
            .param("note", r.note?.trim()?.ifEmpty { null })
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return id
    }

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        changeId: UUID,
    ): List<Redline> {
        versioning.get(actor, MasterId(changeId))
        return views(actor, rows(changeId))
    }

    override fun add(
        actor: Actor,
        changeId: UUID,
        request: AddRedline,
    ): Redline {
        val change = editableChange(actor, changeId)
        val target = versioning.get(actor, MasterId(request.targetId))
        if (target.id == change.id) throw ValidationException("INVALID_REDLINE", "A change can't edit itself")
        problem(actor, target, request)?.let { throw ValidationException("INVALID_REDLINE", it) }
        val id = insert(actor, changeId, request)
        return views(actor, rows(changeId)).first { it.id == id }
    }

    override fun remove(
        actor: Actor,
        changeId: UUID,
        redlineId: UUID,
    ) {
        editableChange(actor, changeId)
        val gone =
            jdbc
                .sql("delete from change_redline where id = :id and change_id = :c and applied_at is null")
                .param("id", redlineId)
                .param("c", changeId)
                .update()
        if (gone == 0) throw NotFoundException("REDLINE_NOT_FOUND", "No such pending edit on this change")
    }

    override fun mass(
        actor: Actor,
        changeId: UUID,
        request: MassChange,
    ): MassChangeResult {
        editableChange(actor, changeId)
        if (request.op == RedlineOp.ADD) throw ValidationException("INVALID_REDLINE", "Adding is done part by part")
        val affected = changes.rows(changeId = changeId).filter { it.role == ChangeRole.AFFECTED }.map { it.targetId }
        if (affected.isEmpty()) throw ValidationException("INVALID_REDLINE", "The change has no affected objects")
        val objects = find(actor, affected)
        val skipped = mutableListOf<MassChangeSkip>()
        val created = mutableListOf<UUID>()
        for (id in affected) {
            val o = objects[id]
            if (o == null) {
                skipped += MassChangeSkip(id, null, "You can't read it")
                continue
            }
            val r =
                AddRedline(
                    request.op,
                    id,
                    request.childId,
                    request.newChildId,
                    request.quantity,
                    request.attribute,
                    request.value,
                    request.note,
                )
            val why = problem(actor, o, r)
            if (why != null) skipped += MassChangeSkip(id, o.number, why) else created += insert(actor, changeId, r)
        }
        val all = views(actor, rows(changeId))
        return MassChangeResult(all.filter { it.id in created }, skipped)
    }

    /**
     * Applies [changeId]'s pending edits as [system] (roadmap 5.11): each target is checked out
     * (revised first when its state allows no edits), edited, checked in, and becomes a resulting
     * object of the change and of the notice it is a task of. Anything that fails refuses the
     * resolution that triggered it.
     */
    internal fun apply(
        system: Actor,
        changeId: UUID,
        number: String,
    ) {
        val pending = rows(changeId).filter { it.appliedAt == null }
        if (pending.isEmpty()) return

        fun refuse(message: String): Nothing = throw ConflictException("REDLINE_NOT_APPLICABLE", message)
        val notice = changes.rows(targetId = changeId).firstOrNull { it.role == ChangeRole.TASK }?.changeId
        for ((targetId, edits) in pending.groupBy { it.targetId }) {
            var o =
                versioning.list(system, ObjectQuery(ids = listOf(MasterId(targetId)), limit = 1)).firstOrNull()
                    ?: refuse("An object $number edits no longer exists")
            if (o.checkedOutBy != null) refuse("${o.number} is checked out by ${o.checkedOutBy}: $number can't edit it")
            val lifecycle = versioning.lifecycle(system, o.id)
            if (!lifecycle.editable) {
                if (!lifecycle.revisable) refuse("${o.number} is ${o.latest.state}: it can't be edited or revised")
                o = versioning.revise(system, o.id)
            }
            versioning.checkOut(system, o.id)
            for (e in edits) {
                val lines = { bom.usages(system, o.id, workingCopy = true).filter { it.child.id == e.childId } }
                when (e.op) {
                    RedlineOp.ADD ->
                        bom.add(
                            system,
                            o.id,
                            AddUsage(e.childId!!, e.quantity ?: BigDecimal.ONE, notes = e.note),
                        )
                    RedlineOp.REMOVE ->
                        lines().ifEmpty { refuse("${o.number} no longer uses the part to remove") }.forEach {
                            bom.remove(system, o.id, it.id)
                        }
                    RedlineOp.QUANTITY ->
                        lines().ifEmpty { refuse("${o.number} no longer uses the part to change") }.forEach {
                            bom.update(
                                system,
                                o.id,
                                it.id,
                                UpdateUsage(
                                    e.quantity!!,
                                    it.unit,
                                    it.lineNumber,
                                    it.findNumber,
                                    it.notes,
                                    it.designators.joinToString(", ").ifEmpty { null },
                                ),
                            )
                        }
                    RedlineOp.REPLACE ->
                        lines().ifEmpty { refuse("${o.number} no longer uses the part to replace") }.forEach {
                            bom.replace(system, o.id, it.id, e.newChildId!!)
                        }
                    RedlineOp.SET_ATTRIBUTE -> {
                        val copy = versioning.workingCopy(system, o.id)
                        versioning.updateWorkingCopy(system, o.id, copy.attributes + (e.attribute!! to e.value))
                    }
                }
            }
            val done = versioning.checkIn(system, o.id, "Edits of $number")
            jdbc
                .sql("update change_redline set applied_at = :at, applied_version = :v where id in (:ids)")
                .param("at", Timestamp.from(clock.instant()))
                .param("v", done.latest.version)
                .param("ids", edits.map { it.id })
                .update()
            for (holder in listOfNotNull(changeId, notice)) {
                jdbc
                    .sql(
                        """
                        insert into change_link (id, tenant_id, change_id, target_id, role, version, note, created_at, created_by)
                        values (:id, :t, :c, :target, 'RESULTING', :v, :note, :at, :by)
                        on conflict (change_id, target_id, role) do nothing
                        """.trimIndent(),
                    ).param("id", UuidV7.generate(clock))
                    .param("t", system.tenant.value)
                    .param("c", holder)
                    .param("target", targetId)
                    .param("v", done.latest.version)
                    .param("note", "Edited by $number")
                    .param("at", Timestamp.from(clock.instant()))
                    .param("by", system.principal.value)
                    .update()
            }
            log.info("{} applied {} edit(s) to {} ({})", number, edits.size, o.number, done.latest.version)
        }
    }
}

/** Applies a change's edits when it is resolved, before a notice releases its resulting objects. */
@Component
internal class RedlineApplication(
    private val redlines: DefaultRedlineService,
    private val types: TypeService,
) {
    @Order(0)
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "versioning.ObjectStateChanged.v1" || envelope.payload["to"] != "RESOLVED") return
        val system = Actor(TenantId(envelope.tenantId), PrincipalId(PROCESS_ACTOR), siteAdmin = true)
        val typeId = TypeId(UUID.fromString(envelope.payload["typeId"].toString()))
        val kind = changeKind(types, system, typeId)
        if (kind != ChangeKind.CHANGE_NOTICE && kind != ChangeKind.CHANGE_TASK) return
        redlines.apply(
            system,
            UUID.fromString(envelope.payload["objectId"].toString()),
            envelope.payload["number"].toString(),
        )
    }
}
