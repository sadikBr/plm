package com.plm.bom.infra

import com.plm.access.api.SqlPredicate
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class AlternateRow(
    val alternateId: UUID,
    val twoWay: Boolean,
    val notes: String?,
    val createdAt: Instant,
    val createdBy: String,
)

internal data class SubstituteRow(
    val substituteId: UUID,
    val quantity: BigDecimal?,
    val notes: String?,
)

internal data class SubstituteUseRow(
    val parentMasterId: UUID,
    val lineNumber: Int,
    val originalId: UUID,
    val quantity: BigDecimal,
    val unit: String,
)

@Repository
internal class ReplacementRepository(
    private val jdbc: JdbcClient,
) {
    fun alternates(part: UUID): List<AlternateRow> =
        jdbc
            .sql(
                """
                select a.alternate_id, a.notes, a.created_at, a.created_by,
                       exists (select 1 from part_alternate b where b.part_id = a.alternate_id and b.alternate_id = a.part_id) as two_way
                from part_alternate a where a.part_id = :part order by a.created_at
                """.trimIndent(),
            ).param("part", part)
            .query { rs, _ ->
                AlternateRow(
                    rs.getObject("alternate_id", UUID::class.java),
                    rs.getBoolean("two_way"),
                    rs.getString("notes"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("created_by"),
                )
            }.list()

    fun addAlternate(
        tenant: TenantId,
        part: UUID,
        alternate: UUID,
        notes: String?,
        at: Instant,
        by: String,
    ) {
        jdbc
            .sql(
                """
                insert into part_alternate (tenant_id, part_id, alternate_id, notes, created_at, created_by) values (:tenant, :part, :alt, :notes, :at, :by)
                on conflict (part_id, alternate_id) do update set notes = excluded.notes
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("part", part)
            .param("alt", alternate)
            .param("notes", notes)
            .param("at", Timestamp.from(at))
            .param("by", by)
            .update()
    }

    fun removeAlternate(
        part: UUID,
        alternate: UUID,
    ): Int =
        jdbc
            .sql(
                "delete from part_alternate where (part_id = :a and alternate_id = :b) or (part_id = :b and alternate_id = :a)",
            ).param("a", part)
            .param("b", alternate)
            .update()

    fun substitutes(usage: UUID): List<SubstituteRow> =
        jdbc
            .sql("select * from usage_substitute where usage_id = :u order by substitute_id")
            .param("u", usage)
            .query { rs, _ ->
                SubstituteRow(
                    rs.getObject("substitute_id", UUID::class.java),
                    rs.getBigDecimal("quantity"),
                    rs.getString("notes"),
                )
            }.list()

    fun addSubstitute(
        usage: UUID,
        substitute: UUID,
        quantity: BigDecimal?,
        notes: String?,
    ) {
        jdbc
            .sql(
                """
                insert into usage_substitute (usage_id, substitute_id, quantity, notes) values (:u, :s, :q, :n)
                on conflict (usage_id, substitute_id) do update set quantity = excluded.quantity, notes = excluded.notes
                """.trimIndent(),
            ).param("u", usage)
            .param("s", substitute)
            .param("q", quantity)
            .param("n", notes)
            .update()
    }

    fun removeSubstitute(
        usage: UUID,
        substitute: UUID,
    ): Int =
        jdbc
            .sql("delete from usage_substitute where usage_id = :u and substitute_id = :s")
            .param("u", usage)
            .param("s", substitute)
            .update()

    fun substituteCounts(usages: Collection<UUID>): Map<UUID, Int> {
        if (usages.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                "select usage_id, count(*) as n from usage_substitute where usage_id = any(cast(:ids as uuid[])) group by usage_id",
            ).param("ids", usages.toTypedArray())
            .query { rs, _ -> rs.getObject("usage_id", UUID::class.java) to rs.getInt("n") }
            .list()
            .toMap()
    }

    fun alternateCounts(parts: Collection<UUID>): Map<UUID, Int> {
        if (parts.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                "select part_id, count(*) as n from part_alternate where part_id = any(cast(:ids as uuid[])) group by part_id",
            ).param("ids", parts.toTypedArray())
            .query { rs, _ -> rs.getObject("part_id", UUID::class.java) to rs.getInt("n") }
            .list()
            .toMap()
    }

    /** Lines of readable parents' latest iterations naming [part] as a substitute. */
    fun substituteUses(
        part: UUID,
        readable: SqlPredicate,
    ): List<SubstituteUseRow> =
        jdbc
            .sql(
                """
                select m.id as parent_id, ul.line_number, ul.child_master_id, ul.quantity, ul.unit
                from usage_substitute s
                join usage_link ul on ul.id = s.usage_id
                join object_master m on m.latest_iteration_id = ul.parent_iteration_id
                join object_iteration li on li.id = m.latest_iteration_id
                where s.substitute_id = :part and ${readable.sql}
                order by m.number, ul.line_number
                """.trimIndent(),
            ).params(readable.params)
            .param("part", part)
            .query { rs, _ ->
                SubstituteUseRow(
                    rs.getObject("parent_id", UUID::class.java),
                    rs.getInt("line_number"),
                    rs.getObject("child_master_id", UUID::class.java),
                    rs.getBigDecimal("quantity").stripTrailingZeros().let {
                        if (it.scale() <
                            0
                        ) {
                            it.setScale(0)
                        } else {
                            it
                        }
                    },
                    rs.getString("unit"),
                )
            }.list()
}
