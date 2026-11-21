package com.plm.effectivity.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Permission
import com.plm.effectivity.api.AddEffectivity
import com.plm.effectivity.api.Effectivity
import com.plm.effectivity.api.EffectivityContext
import com.plm.effectivity.api.EffectivityItem
import com.plm.effectivity.api.EffectivityKind
import com.plm.effectivity.api.EffectivityService
import com.plm.effectivity.api.EffectivityStatus
import com.plm.effectivity.api.EffectivityTarget
import com.plm.effectivity.api.SetEffectivity
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A range of one kind of value: numbers, dates or text, compared as such. No end: open. */
internal data class Range(
    val kind: EffectivityKind,
    val from: Comparable<Any>,
    val to: Comparable<Any>?,
) {
    fun overlaps(other: Range): Boolean =
        (other.to == null || from <= other.to as Any) && (to == null || other.from <= to as Any)

    fun covers(value: Comparable<Any>): Boolean = from <= value as Any && (to == null || value <= to as Any)

    fun text(v: Any?): String? =
        when (v) {
            null -> null
            is BigDecimal -> v.toPlainString()
            else -> v.toString()
        }

    companion object {
        private fun bad(message: String): Nothing = throw ValidationException("INVALID_EFFECTIVITY", message)

        /** A value of [kind], from its text: a yyyy-mm-dd date, a whole number, or a lot name. */
        @Suppress("UNCHECKED_CAST")
        fun value(
            kind: EffectivityKind,
            text: String,
        ): Comparable<Any> {
            val t = text.trim()
            return when (kind) {
                EffectivityKind.DATE ->
                    runCatching { LocalDate.parse(t) }.getOrElse { bad("'$text' isn't a date (yyyy-mm-dd)") }
                EffectivityKind.SERIAL, EffectivityKind.BLOCK ->
                    t
                        .toBigDecimalOrNull()
                        ?.takeIf {
                            it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0
                        }?.setScale(
                            0,
                        )
                        ?: bad("'$text' isn't a ${kind.name.lowercase()} number")
                EffectivityKind.LOT ->
                    t.takeIf { it.isNotEmpty() && it.length <= 100 }
                        ?: bad("Name the lot (at most 100 characters)")
            } as Comparable<Any>
        }

        fun of(
            kind: EffectivityKind,
            from: String,
            to: String?,
        ): Range {
            val f = value(kind, from)
            val t = to?.takeIf { it.isNotBlank() }?.let { value(kind, it) }
            if (t != null && t < f) bad("The range ends before it starts")
            return Range(kind, f, t)
        }
    }
}

internal data class Row(
    val id: UUID,
    val tenantId: UUID,
    val masterId: UUID,
    val revision: String?,
    val lineId: UUID?,
    val contextId: UUID?,
    val range: Range,
    val status: EffectivityStatus,
    val changeId: UUID?,
    val createdAt: java.time.Instant,
    val createdBy: String,
)

@Suppress("UNCHECKED_CAST")
internal fun rowOf(rs: ResultSet): Row {
    val kind = EffectivityKind.valueOf(rs.getString("kind"))
    val (from, to) =
        when (kind) {
            EffectivityKind.DATE -> rs.getDate("from_date").toLocalDate() to rs.getDate("to_date")?.toLocalDate()
            EffectivityKind.SERIAL, EffectivityKind.BLOCK -> rs.getBigDecimal("from_num") to rs.getBigDecimal("to_num")
            EffectivityKind.LOT -> rs.getString("from_text") to rs.getString("to_text")
        }
    return Row(
        rs.getObject("id", UUID::class.java),
        rs.getObject("tenant_id", UUID::class.java),
        rs.getObject("master_id", UUID::class.java),
        rs.getString("revision"),
        rs.getObject("line_id", UUID::class.java),
        rs.getObject("context_id", UUID::class.java),
        Range(kind, from as Comparable<Any>, to as Comparable<Any>?),
        EffectivityStatus.valueOf(rs.getString("status")),
        rs.getObject("change_id", UUID::class.java),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by"),
    )
}

@Service
@Transactional
internal class DefaultEffectivityService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val access: AccessService,
    private val types: TypeService,
    private val clock: Clock,
) : EffectivityService {
    private fun bad(message: String): Nothing = throw ValidationException("INVALID_EFFECTIVITY", message)

    private fun part(
        actor: Actor,
        id: UUID,
    ): VersionedObject {
        val o = versioning.get(actor, MasterId(id))
        if (TypeId.PART !in types.lineage(actor, o.typeId)) bad("${o.number} isn't a part: only parts have effectivity")
        return o
    }

    private fun requireModify(
        actor: Actor,
        o: VersionedObject,
    ) = access.require(actor, AccessTarget(o.contextId, o.typeId, o.latest.state, o.id.value), Permission.MODIFY)

    /** Parts among [ids] the actor may read, by id. */
    private fun readable(
        actor: Actor,
        ids: Collection<UUID>,
    ): Map<UUID, EffectivityItem> {
        if (ids.isEmpty()) return emptyMap()
        return versioning
            .list(actor, ObjectQuery(ids = ids.distinct().map { MasterId(it) }, limit = ids.size))
            .associate { it.id.value to EffectivityItem(it.id.value, it.number, it.name) }
    }

    // ---- contexts ----

    private data class ContextRow(
        val id: UUID,
        val endItemId: UUID,
        val description: String?,
        val createdAt: java.time.Instant,
        val createdBy: String,
    )

    private fun contextRows(
        actor: Actor,
        where: String = "true",
        params: Map<String, Any?> = emptyMap(),
    ): List<ContextRow> =
        jdbc
            .sql("select * from effectivity_context where tenant_id = :t and $where order by created_at")
            .param("t", actor.tenant.value)
            .params(params)
            .query { rs, _ ->
                ContextRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("end_item_id", UUID::class.java),
                    rs.getString("description"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("created_by"),
                )
            }.list()

    private fun contexts(
        actor: Actor,
        rows: List<ContextRow>,
    ): Map<UUID, EffectivityContext> {
        val items = readable(actor, rows.map { it.endItemId })
        return rows.associate {
            it.id to
                EffectivityContext(
                    it.id,
                    items[it.endItemId] ?: EffectivityItem(it.endItemId, null, null),
                    it.description,
                    it.createdAt,
                    it.createdBy,
                )
        }
    }

    @Transactional(readOnly = true)
    override fun contexts(actor: Actor): List<EffectivityContext> {
        val rows = contextRows(actor)
        val items = readable(actor, rows.map { it.endItemId })
        return contexts(actor, rows.filter { it.endItemId in items }).values.toList()
    }

    override fun createContext(
        actor: Actor,
        endItemId: UUID,
        description: String?,
    ): EffectivityContext {
        val o = part(actor, endItemId)
        requireModify(actor, o)
        if (contextRows(actor, "end_item_id = :e", mapOf("e" to endItemId)).isNotEmpty()) {
            throw ConflictException("CONTEXT_EXISTS", "${o.number} is already an end item")
        }
        val id = UuidV7.generate(clock)
        jdbc
            .sql(
                """
                insert into effectivity_context (id, tenant_id, end_item_id, description, created_at, created_by)
                values (:id, :t, :e, :d, :now, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("t", actor.tenant.value)
            .param("e", endItemId)
            .param("d", description?.trim()?.takeIf { it.isNotEmpty() }?.take(500))
            .param("now", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return contexts(actor, contextRows(actor, "id = :id", mapOf("id" to id))).values.single()
    }

    private fun contextFor(
        actor: Actor,
        endItemId: UUID,
    ): UUID =
        contextRows(actor, "end_item_id = :e", mapOf("e" to endItemId)).singleOrNull()?.id
            ?: throw NotFoundException("CONTEXT_NOT_FOUND", "That part isn't an end item yet: make it one first")

    override fun deleteContext(
        actor: Actor,
        contextId: UUID,
    ) {
        val c =
            contextRows(actor, "id = :id", mapOf("id" to contextId)).singleOrNull()
                ?: throw NotFoundException("CONTEXT_NOT_FOUND", "There's no such end item")
        requireModify(actor, part(actor, c.endItemId))
        jdbc.sql("delete from effectivity_context where id = :id").param("id", contextId).update()
    }

    // ---- statements ----

    private fun rows(
        where: String,
        params: Map<String, Any?>,
    ): List<Row> =
        jdbc
            .sql(
                "select * from effectivity where $where order by kind, coalesce(from_date::text, from_num::text, from_text), created_at",
            ).params(params)
            .query { rs, _ -> rowOf(rs) }
            .list()

    private fun view(
        actor: Actor,
        rows: List<Row>,
    ): List<Effectivity> {
        if (rows.isEmpty()) return emptyList()
        val contexts =
            contexts(
                actor,
                contextRows(
                    actor,
                    "id = any(cast(:ids as uuid[]))",
                    mapOf("ids" to rows.mapNotNull { it.contextId }.distinct().toTypedArray()),
                ),
            )
        val lines =
            rows
                .mapNotNull { it.lineId }
                .takeIf { it.isNotEmpty() }
                ?.let { ids ->
                    jdbc
                        .sql(
                            "select id, line_number, child_master_id from usage_link where id = any(cast(:ids as uuid[]))",
                        ).param("ids", ids.toTypedArray())
                        .query { rs, _ ->
                            rs.getObject("id", UUID::class.java) to
                                (rs.getInt("line_number") to rs.getObject("child_master_id", UUID::class.java))
                        }.list()
                        .toMap()
                }.orEmpty()
        val children = readable(actor, lines.values.map { it.second })
        return rows.map { r ->
            val line = r.lineId?.let { lines[it] }
            Effectivity(
                r.id,
                if (r.lineId != null) EffectivityTarget.LINE else EffectivityTarget.VERSION,
                r.masterId,
                r.revision,
                r.lineId,
                line?.first,
                line?.let { children[it.second] ?: EffectivityItem(it.second, null, null) },
                r.contextId?.let { contexts[it] },
                r.range.kind,
                r.range.text(r.range.from)!!,
                r.range.text(r.range.to),
                r.status,
                r.changeId,
                r.createdAt,
                r.createdBy,
            )
        }
    }

    @Transactional(readOnly = true)
    override fun forPart(
        actor: Actor,
        partId: UUID,
        workingCopy: Boolean,
    ): List<Effectivity> {
        val o = part(actor, partId)
        val iteration = if (workingCopy) versioning.workingCopy(actor, o.id).id.value else o.latest.id.value
        return view(
            actor,
            rows(
                """
                master_id = :m and (revision is not null or line_id in (select id from usage_link where parent_iteration_id = :it))
                """.trimIndent(),
                mapOf("m" to partId, "it" to iteration),
            ),
        )
    }

    /** Refuses a range overlapping another of the same kind and end item on the same revisions or line. */
    internal fun checkOverlap(
        candidate: Row,
        except: UUID? = null,
    ) {
        val others =
            rows(
                """
                master_id = :m and kind = :k and status = 'ACTUAL' and context_id is not distinct from :c and id <> :x and
                ${if (candidate.lineId != null) "line_id = :l" else "revision is not null"}
                """.trimIndent(),
                mapOf(
                    "m" to candidate.masterId,
                    "k" to candidate.range.kind.name,
                    "c" to candidate.contextId,
                    "x" to (except ?: UUID(0, 0)),
                    "l" to candidate.lineId,
                ),
            )
        others.firstOrNull { it.range.overlaps(candidate.range) }?.let {
            val what = if (it.lineId != null) "the line's" else "revision ${it.revision}'s"
            throw ConflictException(
                "OVERLAPPING_EFFECTIVITY",
                "It overlaps $what ${it.range.kind.name.lowercase()} effectivity ${it.range.text(
                    it.range.from,
                )} to ${it.range.text(it.range.to) ?: "open"}",
            )
        }
    }

    override fun add(
        actor: Actor,
        partId: UUID,
        request: AddEffectivity,
    ): Effectivity {
        val o = part(actor, partId)
        val range = Range.of(request.kind, request.from, request.to)
        val contextId =
            request.endItemId?.let { contextFor(actor, it) }
                ?: if (request.kind ==
                    EffectivityKind.DATE
                ) {
                    null
                } else {
                    bad("Choose the end item ${request.kind.name.lowercase()} numbers count in")
                }
        val (revision, lineId) =
            when (request.target) {
                EffectivityTarget.VERSION -> {
                    requireModify(actor, o)
                    val rev = request.revision?.trim()?.takeIf { it.isNotEmpty() } ?: bad("Choose the revision")
                    val exists =
                        jdbc
                            .sql("select count(*) from object_iteration where master_id = :m and revision = :r")
                            .param("m", partId)
                            .param("r", rev)
                            .query(Int::class.java)
                            .single() > 0
                    if (!exists) bad("${o.number} has no revision $rev")
                    rev to null
                }
                EffectivityTarget.LINE -> {
                    val wc = versioning.workingCopy(actor, o.id)
                    val line = request.lineId ?: bad("Choose the line")
                    val onIt =
                        jdbc
                            .sql("select count(*) from usage_link where id = :l and parent_iteration_id = :it")
                            .param("l", line)
                            .param("it", wc.id.value)
                            .query(Int::class.java)
                            .single() > 0
                    if (!onIt) bad("That line isn't on your working copy of ${o.number}")
                    null to line
                }
            }
        val id = UuidV7.generate(clock)
        val now = clock.instant()
        val row =
            Row(
                id,
                actor.tenant.value,
                partId,
                revision,
                lineId,
                contextId,
                range,
                if (request.changeId != null) EffectivityStatus.PENDING else EffectivityStatus.ACTUAL,
                request.changeId,
                now,
                actor.principal.value,
            )
        if (row.status == EffectivityStatus.ACTUAL) checkOverlap(row)
        insert(row)
        return view(actor, rows("id = :id", mapOf("id" to id))).single()
    }

    internal fun insert(row: Row) {
        val r = row.range
        jdbc
            .sql(
                """
                insert into effectivity (id, tenant_id, master_id, revision, line_id, context_id, kind, from_num, to_num,
                    from_date, to_date, from_text, to_text, status, change_id, created_at, created_by)
                values (:id, :t, :m, :rev, :line, :ctx, :kind, :fn, :tn, :fd, :td, :ft, :tt, :status, :change, :at, :by)
                """.trimIndent(),
            ).param("id", row.id)
            .param("t", row.tenantId)
            .param("m", row.masterId)
            .param("rev", row.revision)
            .param("line", row.lineId)
            .param("ctx", row.contextId)
            .param("kind", r.kind.name)
            .params(columns(r))
            .param("status", row.status.name)
            .param("change", row.changeId)
            .param("at", Timestamp.from(row.createdAt))
            .param("by", row.createdBy)
            .update()
    }

    /** The range's columns, by kind (the others null). */
    internal fun columns(r: Range): Map<String, Any?> {
        val num = r.kind == EffectivityKind.SERIAL || r.kind == EffectivityKind.BLOCK
        return mapOf(
            "fn" to (if (num) r.from else null),
            "tn" to (if (num) r.to else null),
            "fd" to (if (r.kind == EffectivityKind.DATE) Date.valueOf(r.from as LocalDate) else null),
            "td" to (if (r.kind == EffectivityKind.DATE) (r.to as LocalDate?)?.let { Date.valueOf(it) } else null),
            "ft" to (if (r.kind == EffectivityKind.LOT) r.from else null),
            "tt" to (if (r.kind == EffectivityKind.LOT) r.to else null),
        )
    }

    private fun find(
        actor: Actor,
        id: UUID,
    ): Pair<Row, VersionedObject> {
        val row =
            rows("id = :id and tenant_id = :t", mapOf("id" to id, "t" to actor.tenant.value)).singleOrNull()
                ?: throw NotFoundException("EFFECTIVITY_NOT_FOUND", "There's no such effectivity")
        val o = part(actor, row.masterId)
        if (row.lineId != null) {
            val wc = versioning.workingCopy(actor, o.id)
            val onIt =
                jdbc
                    .sql("select count(*) from usage_link where id = :l and parent_iteration_id = :it")
                    .param("l", row.lineId)
                    .param("it", wc.id.value)
                    .query(Int::class.java)
                    .single() > 0
            if (!onIt) bad("Change a line's effectivity on your working copy")
        } else {
            requireModify(actor, o)
        }
        return row to o
    }

    override fun update(
        actor: Actor,
        effectivityId: UUID,
        request: SetEffectivity,
    ): Effectivity {
        val (row, _) = find(actor, effectivityId)
        val range = Range.of(request.kind, request.from, request.to)
        val contextId =
            request.endItemId?.let { contextFor(actor, it) }
                ?: if (request.kind == EffectivityKind.DATE) null else bad("Choose the end item")
        val changed = row.copy(range = range, contextId = contextId)
        if (changed.status == EffectivityStatus.ACTUAL) checkOverlap(changed, except = row.id)
        jdbc
            .sql(
                """
                update effectivity set kind = :kind, context_id = :ctx, from_num = :fn, to_num = :tn, from_date = :fd,
                    to_date = :td, from_text = :ft, to_text = :tt where id = :id
                """.trimIndent(),
            ).param("id", row.id)
            .param("kind", range.kind.name)
            .param("ctx", contextId)
            .params(columns(range))
            .update()
        return view(actor, rows("id = :id", mapOf("id" to row.id))).single()
    }

    override fun delete(
        actor: Actor,
        effectivityId: UUID,
    ) {
        val (row, _) = find(actor, effectivityId)
        jdbc.sql("delete from effectivity where id = :id").param("id", row.id).update()
    }
}
