package com.plm.bom.infra

import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class LinkRow(
    val id: UUID,
    val parentIterationId: UUID,
    val childMasterId: UUID,
    val lineNumber: Int,
    val findNumber: String?,
    val quantity: BigDecimal,
    val unit: String,
    val notes: String?,
    val createdAt: Instant,
    val createdBy: String,
)

/** A child as a configuration specification resolved it for a reader (roadmap 4.2). */
internal data class Resolved(
    val masterId: UUID,
    val number: String,
    val name: String,
    val typeId: UUID,
    /** Null: no iteration matches the specification. */
    val iterationId: UUID?,
    val version: String?,
    val state: String?,
    val hasChildren: Boolean,
)

@Repository
internal class BomRepository(
    private val jdbc: JdbcClient,
    private val effectivity: org.springframework.beans.factory.ObjectProvider<com.plm.bom.api.EffectivityProvider>,
    private val lineCopies: org.springframework.beans.factory.ObjectProvider<com.plm.bom.api.LineCopyListener>,
) {
    private val lineCopyListeners get() = lineCopies.orderedStream().toList()

    /** The effectivity module's choice of iteration; refused when it isn't there. */
    private fun effectivityPick(spec: com.plm.bom.api.ConfigSpec) =
        (
            effectivity.ifAvailable
                ?: throw com.plm.platform.error
                    .ValidationException("INVALID_SPEC", "Effectivity isn't available")
        ).pick(spec)

    /** Of [links], those effective under [spec] (all of them, unless it's an effectivity spec). */
    fun effective(
        links: List<LinkRow>,
        spec: com.plm.bom.api.ConfigSpec,
    ): List<LinkRow> {
        if (spec.type != com.plm.bom.api.ConfigSpecType.EFFECTIVITY || links.isEmpty()) return links
        val out = effectivity.ifAvailable?.ineffectiveLines(links.map { it.id }, spec).orEmpty()
        return if (out.isEmpty()) links else links.filter { it.id !in out }
    }

    private val mapper =
        RowMapper { rs, _ ->
            LinkRow(
                rs.getObject("id", UUID::class.java),
                rs.getObject("parent_iteration_id", UUID::class.java),
                rs.getObject("child_master_id", UUID::class.java),
                rs.getInt("line_number"),
                rs.getString("find_number"),
                rs.getBigDecimal("quantity").stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it },
                rs.getString("unit"),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"),
            )
        }

    fun links(iterationId: UUID): List<LinkRow> =
        jdbc
            .sql("select * from usage_link where parent_iteration_id = :it order by line_number")
            .param("it", iterationId)
            .query(mapper)
            .list()

    /** The lines of many iterations at once, by iteration. */
    fun linksOf(iterations: Collection<UUID>): Map<UUID, List<LinkRow>> {
        if (iterations.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                "select * from usage_link where parent_iteration_id = any(cast(:its as uuid[])) order by parent_iteration_id, line_number",
            ).param("its", iterations.toTypedArray())
            .query(mapper)
            .list()
            .groupBy { it.parentIterationId }
    }

    /**
     * Picks the iteration of each master [spec] asks for, among those the reader may read
     * ([readable]: an access predicate on `m` and the latest iteration `li`, with its parameters).
     * Masters the reader may not read are left out.
     */
    fun resolve(
        masters: Collection<UUID>,
        spec: com.plm.bom.api.ConfigSpec,
        readable: com.plm.access.api.SqlPredicate,
    ): Map<UUID, Resolved> {
        if (masters.isEmpty()) return emptyMap()
        val pick =
            when (spec.type) {
                com.plm.bom.api.ConfigSpecType.LATEST ->
                    "select * from object_iteration x where x.id = m.latest_iteration_id"
                com.plm.bom.api.ConfigSpecType.STATE ->
                    """
                    select * from object_iteration x where x.master_id = m.id and x.status = 'CHECKED_IN' and x.one_off is null and x.state = :spec_state
                    order by x.seq desc limit 1
                    """.trimIndent()
                com.plm.bom.api.ConfigSpecType.BASELINE ->
                    """
                    select x.* from baseline_member bm join object_iteration x on x.id = bm.iteration_id
                    where bm.baseline_id = :spec_baseline and bm.master_id = m.id
                    """.trimIndent()
                com.plm.bom.api.ConfigSpecType.EFFECTIVITY -> effectivityPick(spec).sql
                com.plm.bom.api.ConfigSpecType.AS_OF ->
                    """
                    select * from object_iteration x where x.master_id = m.id and x.status = 'CHECKED_IN' and x.one_off is null and x.checked_in_at <= :spec_as_of
                    order by x.seq desc limit 1
                    """.trimIndent()
            }
        return jdbc
            .sql(
                """
                select m.id, m.number, m.name, m.type_id, i.id as iteration_id, i.revision, i.iteration, i.state,
                       (i.id is not null and exists (select 1 from usage_link ul where ul.parent_iteration_id = i.id)) as has_children
                from object_master m
                join object_iteration li on li.id = m.latest_iteration_id
                left join lateral ($pick) i on true
                where m.id = any(cast(:ids as uuid[])) and ${readable.sql}
                """.trimIndent(),
            ).params(readable.params)
            .param("ids", masters.toTypedArray())
            .param("spec_state", spec.state ?: "RELEASED")
            .param("spec_as_of", Timestamp.from(spec.asOf ?: Instant.now()))
            .param("spec_baseline", spec.baselineId ?: UUID(0, 0))
            .params(
                if (spec.type ==
                    com.plm.bom.api.ConfigSpecType.EFFECTIVITY
                ) {
                    effectivityPick(spec).params
                } else {
                    emptyMap()
                },
            ).query { rs, _ ->
                val it = rs.getObject("iteration_id", UUID::class.java)
                Resolved(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("number"),
                    rs.getString("name"),
                    rs.getObject("type_id", UUID::class.java),
                    it,
                    it?.let { _ -> rs.getString("revision") + "." + rs.getInt("iteration") },
                    it?.let { _ -> rs.getString("state") },
                    rs.getBoolean("has_children"),
                )
            }.list()
            .associateBy { it.masterId }
    }

    fun link(id: UUID): LinkRow? =
        jdbc
            .sql("select * from usage_link where id = :id")
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    fun insert(
        tenant: TenantId,
        row: LinkRow,
    ) {
        jdbc
            .sql(
                """
                insert into usage_link (id, tenant_id, parent_iteration_id, child_master_id, line_number, find_number,
                                        quantity, unit, notes, created_at, created_by)
                values (:id, :tenant, :parent, :child, :line, :find, :qty, :unit, :notes, :at, :by)
                """.trimIndent(),
            ).param("id", row.id)
            .param("tenant", tenant.value)
            .param("parent", row.parentIterationId)
            .param("child", row.childMasterId)
            .param("line", row.lineNumber)
            .param("find", row.findNumber)
            .param("qty", row.quantity)
            .param("unit", row.unit)
            .param("notes", row.notes)
            .param("at", Timestamp.from(row.createdAt))
            .param("by", row.createdBy)
            .update()
    }

    fun update(row: LinkRow) {
        jdbc
            .sql(
                "update usage_link set line_number = :line, find_number = :find, quantity = :qty, unit = :unit, notes = :notes where id = :id",
            ).param("id", row.id)
            .param("line", row.lineNumber)
            .param("find", row.findNumber)
            .param("qty", row.quantity)
            .param("unit", row.unit)
            .param("notes", row.notes)
            .update()
    }

    fun replaceChild(
        id: UUID,
        child: UUID,
    ) {
        jdbc
            .sql(
                "update usage_link set child_master_id = :child where id = :id",
            ).param("id", id)
            .param("child", child)
            .update()
    }

    fun renumber(
        id: UUID,
        line: Int,
    ) {
        jdbc
            .sql(
                "update usage_link set line_number = :line where id = :id",
            ).param("id", id)
            .param("line", line)
            .update()
    }

    /** The master of each of these iterations. */
    fun mastersOf(iterations: Collection<UUID>): Map<UUID, UUID> =
        jdbc
            .sql("select id, master_id from object_iteration where id = any(cast(:ids as uuid[]))")
            .param("ids", iterations.toTypedArray())
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("master_id", UUID::class.java) }
            .list()
            .toMap()

    /** Reference designators of these lines, in order (roadmap 4.5). */
    fun designators(usageIds: Collection<UUID>): Map<UUID, List<String>> {
        if (usageIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                "select usage_id, designator from usage_occurrence where usage_id = any(cast(:ids as uuid[])) order by usage_id, position",
            ).param("ids", usageIds.toTypedArray())
            .query { rs, _ -> rs.getObject("usage_id", UUID::class.java) to rs.getString("designator") }
            .list()
            .groupBy({ it.first }, { it.second })
    }

    fun setDesignators(
        usageId: UUID,
        designators: List<String>,
    ) {
        jdbc.sql("delete from usage_occurrence where usage_id = :id").param("id", usageId).update()
        designators.forEachIndexed { i, d ->
            jdbc
                .sql("insert into usage_occurrence (usage_id, designator, position) values (:id, :d, :p)")
                .param("id", usageId)
                .param("d", d)
                .param("p", i)
                .update()
        }
    }

    fun copyDesignators(
        from: UUID,
        to: UUID,
    ) {
        jdbc
            .sql(
                "insert into usage_occurrence (usage_id, designator, position) select :to, designator, position from usage_occurrence where usage_id = :from",
            ).param("from", from)
            .param("to", to)
            .update()
    }

    /** Lines using any of [children], with the master of the iteration they're on. */
    fun usingLines(children: Collection<UUID>): List<Pair<LinkRow, UUID>> {
        if (children.isEmpty()) return emptyList()
        return jdbc
            .sql(
                """
                select ul.*, i.master_id from usage_link ul join object_iteration i on i.id = ul.parent_iteration_id
                where ul.child_master_id = any(cast(:ids as uuid[])) and i.status = 'CHECKED_IN'
                """.trimIndent(),
            ).param("ids", children.toTypedArray())
            .query { rs, n -> mapper.mapRow(rs, n) to rs.getObject("master_id", UUID::class.java) }
            .list()
    }

    fun copySubstitutes(
        from: UUID,
        to: UUID,
    ) {
        jdbc
            .sql(
                "insert into usage_substitute (usage_id, substitute_id, quantity, notes) select :to, substitute_id, quantity, notes from usage_substitute where usage_id = :from",
            ).param("from", from)
            .param("to", to)
            .update()
    }

    fun delete(id: UUID) {
        jdbc.sql("delete from usage_link where id = :id").param("id", id).update()
    }

    /** Which of these masters' latest iterations use anything. */
    fun withChildren(masters: Collection<UUID>): Set<UUID> {
        if (masters.isEmpty()) return emptySet()
        return jdbc
            .sql(
                """
                select m.id from object_master m
                where m.id = any(cast(:ids as uuid[]))
                  and exists (select 1 from usage_link ul where ul.parent_iteration_id = m.latest_iteration_id)
                """.trimIndent(),
            ).param("ids", masters.toTypedArray())
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .set()
    }

    /** True if [target] is [from] or anywhere under it (latest iterations), i.e. adding it under [target] would loop. */
    fun reaches(
        from: UUID,
        target: UUID,
    ): Boolean =
        jdbc
            .sql(
                """
                with recursive walk (master_id) as (
                    select cast(:from as uuid)
                    union
                    select ul.child_master_id from walk w
                    join object_master m on m.id = w.master_id
                    join usage_link ul on ul.parent_iteration_id = m.latest_iteration_id
                )
                select exists (select 1 from walk where master_id = :target)
                """.trimIndent(),
            ).param("from", from)
            .param("target", target)
            .query(Boolean::class.java)
            .single()

    /** The lines of the iteration [iterationId] was based on, copied to it (check-out, revise). */
    fun carryOver(
        iterationId: UUID,
        newId: () -> UUID,
    ): Int {
        val source =
            jdbc
                .sql("select based_on_id from object_iteration where id = :id")
                .param("id", iterationId)
                .query { rs, _ -> rs.getObject("based_on_id", UUID::class.java) }
                .optional()
                .orElse(null) ?: return 0
        if (links(iterationId).isNotEmpty()) return 0
        val rows = links(source)
        val tenant =
            jdbc
                .sql(
                    "select m.tenant_id from object_iteration i join object_master m on m.id = i.master_id where i.id = :id",
                ).param("id", iterationId)
                .query { rs, _ -> TenantId(rs.getObject("tenant_id", UUID::class.java)) }
                .single()
        val pairs =
            rows.map {
                val copy = it.copy(id = newId(), parentIterationId = iterationId)
                insert(tenant, copy)
                copyDesignators(it.id, copy.id)
                copySubstitutes(it.id, copy.id)
                it.id to copy.id
            }
        if (pairs.isNotEmpty()) lineCopyListeners.forEach { it.copied(pairs) }
        return rows.size
    }

    /** Parents (masters) any of whose iterations use [child]. */
    fun usedIn(child: UUID): Int =
        jdbc
            .sql(
                """
                select count(distinct i.master_id) from usage_link ul join object_iteration i on i.id = ul.parent_iteration_id
                where ul.child_master_id = :child
                """.trimIndent(),
            ).param("child", child)
            .query(Int::class.java)
            .single()
}
