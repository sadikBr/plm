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

@Repository
internal class BomRepository(
    private val jdbc: JdbcClient,
) {
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
        rows.forEach { insert(tenant, it.copy(id = newId(), parentIterationId = iterationId)) }
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
