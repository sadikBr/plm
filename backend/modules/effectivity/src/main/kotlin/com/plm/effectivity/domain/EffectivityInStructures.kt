package com.plm.effectivity.domain

import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.EffectivityProvider
import com.plm.bom.api.IterationPick
import com.plm.bom.api.LineCopyListener
import com.plm.effectivity.api.EffectivityKind
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Date
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** What an effectivity spec asks: the kind, the value, and the end item it counts in. */
private data class Asked(
    val kind: EffectivityKind,
    val value: Any,
    val endItemId: UUID?,
)

private fun asked(spec: ConfigSpec): Asked {
    val kind =
        spec.effectivityKind?.let { k -> EffectivityKind.entries.firstOrNull { it.name == k.uppercase() } }
            ?: throw ValidationException("INVALID_SPEC", "Choose what effectivity is by: date, serial, lot or block")
    val text =
        spec.effectiveAt?.takeIf { it.isNotBlank() }
            ?: throw ValidationException("INVALID_SPEC", "Choose the ${kind.name.lowercase()}")
    if (kind != EffectivityKind.DATE && spec.endItemId == null) {
        throw ValidationException("INVALID_SPEC", "Choose the end item the ${kind.name.lowercase()} is of")
    }
    val value =
        try {
            Range.value(kind, text)
        } catch (e: ValidationException) {
            throw ValidationException("INVALID_SPEC", e.message ?: "Not a ${kind.name.lowercase()}")
        }
    return Asked(kind, if (value is LocalDate) Date.valueOf(value) else value, spec.endItemId)
}

/** The SQL that's true when effectivity row `e` covers the asked value. */
private fun covers(kind: EffectivityKind): String =
    when (kind) {
        EffectivityKind.DATE -> "e.from_date <= :eff_v and (e.to_date is null or e.to_date >= :eff_v)"
        EffectivityKind.SERIAL, EffectivityKind.BLOCK ->
            "e.from_num <= :eff_v and (e.to_num is null or e.to_num >= :eff_v)"
        EffectivityKind.LOT -> "e.from_text <= :eff_v and (e.to_text is null or e.to_text >= :eff_v)"
    }

/** The SQL that's true when row `e` counts in the asked end item (dates may count everywhere). */
private fun inContext(kind: EffectivityKind): String {
    val ctx = "e.context_id = (select c.id from effectivity_context c where c.end_item_id = :eff_end)"
    return if (kind == EffectivityKind.DATE) "(e.context_id is null or $ctx)" else ctx
}

/**
 * Effectivity in configuration specs (roadmap 7.2): a part with effectivity of the asked kind in
 * the end item is at its latest iteration of the revision effective there (none if no revision
 * is); a part without any is at its latest Released iteration, else its latest. A line with
 * effectivity of that kind is kept only where it covers the value; lines without any always are.
 */
@Component
internal class EffectivityInStructures(
    private val jdbc: JdbcClient,
) : EffectivityProvider {
    override fun pick(spec: ConfigSpec): IterationPick {
        val a = asked(spec)
        val any =
            "e.master_id = m.id and e.revision is not null and e.status = 'ACTUAL' and e.kind = :eff_kind and " +
                inContext(a.kind)
        val sql =
            """
            select * from (
              (select x.*, 1 as eff_rank from object_iteration x
               where x.master_id = m.id and x.status = 'CHECKED_IN' and x.one_off is null
                 and x.revision = (select e.revision from effectivity e where $any and ${covers(
                a.kind,
            )} order by e.created_at desc limit 1)
               order by x.seq desc limit 1)
              union all
              (select x.*, 2 from object_iteration x
               where x.master_id = m.id and x.status = 'CHECKED_IN' and x.one_off is null and not exists (select 1 from effectivity e where $any)
               order by (x.state = 'RELEASED') desc, x.seq desc limit 1)
            ) eff order by eff_rank limit 1
            """.trimIndent()
        return IterationPick(
            sql,
            mapOf(
                "eff_kind" to a.kind.name,
                "eff_v" to a.value,
                "eff_end" to (a.endItemId ?: UUID(0, 0)),
            ),
        )
    }

    override fun ineffectiveLines(
        lineIds: Collection<UUID>,
        spec: ConfigSpec,
    ): Set<UUID> {
        if (lineIds.isEmpty()) return emptySet()
        val a = asked(spec)
        return jdbc
            .sql(
                """
                select e.line_id from effectivity e
                where e.line_id = any(cast(:ids as uuid[])) and e.status = 'ACTUAL' and e.kind = :eff_kind and ${inContext(
                    a.kind,
                )}
                group by e.line_id having not bool_or(${covers(a.kind)})
                """.trimIndent(),
            ).param("ids", lineIds.toTypedArray())
            .param("eff_kind", a.kind.name)
            .param("eff_v", a.value)
            .param("eff_end", a.endItemId ?: UUID(0, 0))
            .query { rs, _ -> rs.getObject(1, UUID::class.java) }
            .set()
    }
}

/** A line's effectivity follows the line into the next iteration (check-out, revise). */
@Component
internal class LineEffectivityFollows(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : LineCopyListener {
    override fun copied(lines: List<Pair<UUID, UUID>>) {
        for ((old, new) in lines) {
            val ids =
                jdbc
                    .sql("select id from effectivity where line_id = :l")
                    .param("l", old)
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                    .list()
            for (id in ids) {
                jdbc
                    .sql(
                        """
                        insert into effectivity (id, tenant_id, master_id, revision, line_id, context_id, kind, from_num, to_num,
                            from_date, to_date, from_text, to_text, status, change_id, created_at, created_by)
                        select :new, tenant_id, master_id, null, :line, context_id, kind, from_num, to_num, from_date, to_date,
                            from_text, to_text, status, change_id, created_at, created_by
                        from effectivity where id = :id
                        """.trimIndent(),
                    ).param("new", UuidV7.generate(clock))
                    .param("line", new)
                    .param("id", id)
                    .update()
            }
        }
    }
}
