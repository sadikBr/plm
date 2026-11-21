package com.plm.variants.domain

import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.LineCopyListener
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ValidationException
import com.plm.variants.api.ConditionService
import com.plm.variants.api.ConfiguredLine
import com.plm.variants.api.ConfiguredStructure
import com.plm.variants.api.LineCondition
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultConditionService(
    private val jdbc: JdbcClient,
    private val options: DefaultOptionService,
    private val rules: DefaultOptionRuleService,
    private val versioning: VersioningService,
    private val bom: BomService,
    private val clock: Clock,
) : ConditionService {
    internal fun rows(lineIds: Collection<UUID>): Map<UUID, LineCondition> {
        if (lineIds.isEmpty()) return emptyMap()
        return jdbc
            .sql("select line_id, expression, options from line_condition where line_id = any(cast(:ids as uuid[]))")
            .param("ids", lineIds.distinct().toTypedArray())
            .query { rs, _ ->
                LineCondition(
                    rs.getObject("line_id", UUID::class.java),
                    rs.getString("expression"),
                    (rs.getArray("options").array as Array<*>).map { it.toString() },
                )
            }.list()
            .associateBy { it.lineId }
    }

    private fun lineIdsOf(iteration: UUID): List<UUID> =
        jdbc
            .sql("select id from usage_link where parent_iteration_id = :it")
            .param("it", iteration)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) }
            .list()

    @Transactional(readOnly = true)
    override fun conditions(
        actor: Actor,
        partId: UUID,
        workingCopy: Boolean,
    ): List<LineCondition> {
        val o = versioning.get(actor, MasterId(partId))
        val it = if (workingCopy) versioning.workingCopy(actor, o.id).id.value else o.latest.id.value
        return rows(lineIdsOf(it)).values.toList()
    }

    @Transactional(readOnly = true)
    override fun forLines(
        actor: Actor,
        lineIds: List<UUID>,
    ): List<LineCondition> {
        if (lineIds.isEmpty()) return emptyList()
        if (lineIds.size > 5000) throw ValidationException("INVALID_CONDITION", "At most 5,000 lines at once")
        val found = rows(lineIds)
        if (found.isEmpty()) return emptyList()
        // Only lines of parts the reader can read.
        val parents =
            jdbc
                .sql(
                    """
                    select ul.id, i.master_id from usage_link ul join object_iteration i on i.id = ul.parent_iteration_id
                    where ul.id = any(cast(:ids as uuid[]))
                    """.trimIndent(),
                ).param("ids", found.keys.toTypedArray())
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
                .list()
                .toMap()
        val readable =
            versioning
                .list(actor, ObjectQuery(ids = parents.values.distinct().map { MasterId(it) }, limit = parents.size))
                .map { it.id.value }
                .toSet()
        return found.values.filter { parents[it.lineId] in readable }
    }

    override fun setCondition(
        actor: Actor,
        partId: UUID,
        lineId: UUID,
        expression: String?,
    ): LineCondition? {
        val o = versioning.get(actor, MasterId(partId))
        val setId =
            options.setOf(partId)
                ?: throw ValidationException(
                    "NOT_CONFIGURABLE",
                    "${o.number} isn't configurable: give it an option set first",
                )
        val wc = versioning.workingCopy(actor, o.id)
        if (lineId !in lineIdsOf(wc.id.value)) {
            throw ValidationException("INVALID_CONDITION", "That line isn't on your working copy of ${o.number}")
        }
        if (expression.isNullOrBlank()) {
            jdbc.sql("delete from line_condition where line_id = :l").param("l", lineId).update()
            return null
        }
        val set = options.get(actor, setId)
        val condition = Condition.parse(expression, set)
        val text = expression.trim().replace(Regex("\\s+"), " ")
        jdbc
            .sql(
                """
                insert into line_condition (line_id, tenant_id, set_id, expression, options, updated_at, updated_by)
                values (:l, :t, :s, :e, :o, :now, :by)
                on conflict (line_id) do update set set_id = excluded.set_id, expression = excluded.expression,
                    options = excluded.options, updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("l", lineId)
            .param("t", actor.tenant.value)
            .param("s", setId)
            .param("e", text)
            .param("o", condition.options().sorted().toTypedArray())
            .param("now", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return rows(listOf(lineId))[lineId]
    }

    @Transactional(readOnly = true)
    override fun configured(
        actor: Actor,
        partId: UUID,
        selection: Map<String, String>,
        spec: ConfigSpec,
    ): ConfiguredStructure {
        val normalized =
            selection.entries
                .associate { (k, v) ->
                    k.trim().uppercase() to v.trim().uppercase()
                }.filterValues { it.isNotEmpty() }
        val set = options.setOf(partId)?.let { runCatching { options.get(actor, it) }.getOrNull() }
        val structure = bom.structure(actor, MasterId(partId), spec, depth = 50)
        val conditions = rows(structure.nodes.map { it.line.id })
        val applies = HashMap<String, Boolean>()
        val lines =
            structure.nodes.map { n ->
                val c = conditions[n.line.id]
                val own =
                    c == null || runCatching { Condition.parse(c.expression).holds(normalized) }.getOrDefault(false)
                val yes = own && (n.parentPath == null || applies[n.parentPath] == true)
                applies[n.path] = yes
                ConfiguredLine(n, c?.expression, yes)
            }
        return ConfiguredStructure(
            partId,
            structure.rootIterationId,
            structure.rootVersion,
            set,
            normalized,
            set?.let { rules.check(it, normalized, allowRetired = true) },
            lines,
            lines.count { it.applies },
            lines.size,
        )
    }
}

/** A line's condition follows it into the next iteration (check-out, revise). */
@Component
internal class ConditionsFollowLines(
    private val jdbc: JdbcClient,
) : LineCopyListener {
    override fun copied(lines: List<Pair<UUID, UUID>>) {
        for ((old, new) in lines) {
            jdbc
                .sql(
                    """
                    insert into line_condition (line_id, tenant_id, set_id, expression, options, updated_at, updated_by)
                    select :new, tenant_id, set_id, expression, options, updated_at, updated_by from line_condition where line_id = :old
                    on conflict do nothing
                    """.trimIndent(),
                ).param("new", new)
                .param("old", old)
                .update()
        }
    }
}

/** Conditions keep the options they're about. */
@Component
internal class ConditionsUseOptions(
    private val jdbc: JdbcClient,
) : OptionUsage {
    override fun ensureUnused(optionId: UUID) {
        val used =
            jdbc
                .sql(
                    """
                    select count(*) from line_condition lc join option_def o on o.set_id = lc.set_id
                    where o.id = :o and o.code = any(lc.options)
                    """.trimIndent(),
                ).param("o", optionId)
                .query(Int::class.java)
                .single()
        if (used >
            0
        ) {
            throw ConflictException("OPTION_IN_USE", "$used line condition(s) use this option: change them first")
        }
    }
}
