package com.plm.variants.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.Permission
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.variants.api.ChoiceRef
import com.plm.variants.api.OptionRule
import com.plm.variants.api.OptionRuleService
import com.plm.variants.api.OptionSet
import com.plm.variants.api.RuleKind
import com.plm.variants.api.SaveRule
import com.plm.variants.api.SelectionCheck
import com.plm.variants.api.SelectionProblem
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private fun ChoiceRef.text() = "$option=$choice"

/** A set's choices, both ways: by id, and by option and choice code. */
internal class ChoiceIndex(
    set: OptionSet,
) {
    val byId: Map<UUID, ChoiceRef> =
        set.options.flatMap { o -> o.choices.map { it.id to ChoiceRef(o.code, it.code) } }.toMap()
    private val byRef = byId.entries.associate { (id, ref) -> ref to id }

    fun id(ref: ChoiceRef): UUID =
        byRef[ChoiceRef(ref.option.trim().uppercase(), ref.choice.trim().uppercase())]
            ?: throw ValidationException("INVALID_RULE", "There's no choice ${ref.text()} in this set")
}

@Service
@Transactional
internal class DefaultOptionRuleService(
    private val jdbc: JdbcClient,
    private val options: DefaultOptionService,
    private val access: AccessService,
    private val clock: Clock,
) : OptionRuleService {
    private data class RuleRow(
        val id: UUID,
        val kind: RuleKind,
        val ifChoice: UUID,
        val thenChoices: List<UUID>,
        val note: String?,
    )

    private fun rows(setId: UUID): List<RuleRow> =
        jdbc
            .sql("select * from option_rule where set_id = :s order by created_at, id")
            .param("s", setId)
            .query { rs, _ ->
                RuleRow(
                    rs.getObject("id", UUID::class.java),
                    RuleKind.valueOf(rs.getString("kind")),
                    rs.getObject("if_choice", UUID::class.java),
                    (rs.getArray("then_choices").array as Array<*>).map { it as UUID },
                    rs.getString("note"),
                )
            }.list()

    private fun view(
        set: OptionSet,
        rows: List<RuleRow>,
    ): List<OptionRule> {
        val idx = ChoiceIndex(set)
        return rows.map { r ->
            val ifRef = idx.byId[r.ifChoice]!!
            val thens = r.thenChoices.mapNotNull { idx.byId[it] }
            val verb =
                when (r.kind) {
                    RuleKind.INCLUDES -> "includes"
                    RuleKind.REQUIRES -> "requires one of"
                    RuleKind.EXCLUDES -> "excludes"
                }
            OptionRule(
                r.id,
                r.kind,
                ifRef,
                thens,
                r.note,
                "${ifRef.text()} $verb ${thens.joinToString(", ") { it.text() }}",
            )
        }
    }

    @Transactional(readOnly = true)
    override fun rules(
        actor: Actor,
        setId: UUID,
    ): List<OptionRule> = view(options.get(actor, setId), rows(setId))

    private fun requireModify(
        actor: Actor,
        set: OptionSet,
    ) = access.require(actor, AccessTarget(ContextId(set.contextId)), Permission.MODIFY)

    override fun addRule(
        actor: Actor,
        setId: UUID,
        request: SaveRule,
    ): List<OptionRule> {
        val set = options.get(actor, setId)
        requireModify(actor, set)
        val idx = ChoiceIndex(set)
        val ifId = idx.id(request.ifChoice)
        if (request.thenChoices.isEmpty()) {
            throw ValidationException(
                "INVALID_RULE",
                "Name the choices the rule is about",
            )
        }
        val thenIds = request.thenChoices.map { idx.id(it) }.distinct()
        if (ifId in thenIds) throw ValidationException("INVALID_RULE", "A choice can't be a rule about itself")
        val ifOption = idx.byId[ifId]!!.option
        if (request.kind != RuleKind.EXCLUDES && thenIds.any { idx.byId[it]!!.option == ifOption }) {
            throw ValidationException(
                "INVALID_RULE",
                "Only one choice per option is made: it can't also include or require another of $ifOption",
            )
        }
        if (request.kind == RuleKind.INCLUDES && thenIds.groupBy { idx.byId[it]!!.option }.any { it.value.size > 1 }) {
            throw ValidationException("INVALID_RULE", "A rule can't include two choices of the same option")
        }
        jdbc
            .sql(
                """
                insert into option_rule (id, set_id, kind, if_choice, then_choices, note, created_at, created_by)
                values (:id, :s, :k, :if, :then, :note, :now, :by)
                """.trimIndent(),
            ).param("id", UuidV7.generate(clock))
            .param("s", setId)
            .param("k", request.kind.name)
            .param("if", ifId)
            .param("then", thenIds.toTypedArray())
            .param("note", request.note?.trim()?.takeIf { it.isNotEmpty() })
            .param("now", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return rules(actor, setId)
    }

    override fun removeRule(
        actor: Actor,
        ruleId: UUID,
    ): List<OptionRule> {
        val setId =
            jdbc
                .sql("select set_id from option_rule where id = :id")
                .param("id", ruleId)
                .query(UUID::class.java)
                .optional()
                .orElseThrow { NotFoundException("RULE_NOT_FOUND", "There's no such rule") }
        requireModify(actor, options.get(actor, setId))
        jdbc.sql("delete from option_rule where id = :id").param("id", ruleId).update()
        return rules(actor, setId)
    }

    @Transactional(readOnly = true)
    override fun check(
        actor: Actor,
        setId: UUID,
        selection: Map<String, String>,
        allowRetired: Boolean,
    ): SelectionCheck {
        val set = options.get(actor, setId)
        return check(set, rows(setId), selection, allowRetired)
    }

    private fun check(
        set: OptionSet,
        rules: List<RuleRow>,
        raw: Map<String, String>,
        allowRetired: Boolean,
    ): SelectionCheck {
        val problems = ArrayList<SelectionProblem>()
        val selection =
            raw.entries
                .associate { (k, v) ->
                    k.trim().uppercase() to v.trim().uppercase()
                }.filterValues { it.isNotEmpty() }
        val chosen = HashSet<UUID>()
        for ((code, choice) in selection) {
            val option = set.options.firstOrNull { it.code == code }
            val c = option?.choices?.firstOrNull { it.code == choice }
            when {
                option == null -> problems += SelectionProblem("UNKNOWN", "There's no option $code")
                c == null -> problems += SelectionProblem("UNKNOWN", "$code has no choice $choice")
                c.retired && !allowRetired ->
                    problems +=
                        SelectionProblem(
                            "RETIRED",
                            "$code=$choice is retired",
                            fixes = option.choices.filter { !it.retired }.map { ChoiceRef(code, it.code) },
                        )
                else -> chosen += c.id
            }
        }
        for (o in set.options.filter { it.required && it.code !in selection }) {
            problems +=
                SelectionProblem(
                    "MISSING",
                    "Choose a ${o.name.lowercase()} (${o.code})",
                    fixes =
                        o.choices
                            .filter {
                                !it.retired
                            }.map { ChoiceRef(o.code, it.code) },
                )
        }
        val idx = ChoiceIndex(set)
        for (r in rules.filter { it.ifChoice in chosen }) {
            val ifRef = idx.byId[r.ifChoice]!!
            val then = r.thenChoices.mapNotNull { id -> idx.byId[id]?.let { id to it } }
            when (r.kind) {
                RuleKind.INCLUDES -> {
                    val missing = then.filter { it.first !in chosen }.map { it.second }
                    if (missing.isNotEmpty()) {
                        problems +=
                            SelectionProblem(
                                "RULE",
                                "${ifRef.text()} includes ${missing.joinToString(", ") { it.text() }}",
                                r.id,
                                fixes = missing,
                            )
                    }
                }
                RuleKind.REQUIRES ->
                    if (then.none { it.first in chosen }) {
                        problems +=
                            SelectionProblem(
                                "RULE",
                                "${ifRef.text()} requires one of ${then.joinToString(
                                    ", ",
                                ) { it.second.text() }}",
                                r.id,
                                fixes =
                                    then.map {
                                        it.second
                                    },
                            )
                    }
                RuleKind.EXCLUDES -> {
                    val clash = then.filter { it.first in chosen }.map { it.second }
                    if (clash.isNotEmpty()) {
                        problems +=
                            SelectionProblem(
                                "RULE",
                                "${ifRef.text()} excludes ${clash.joinToString(", ") { it.text() }}",
                                r.id,
                                drop =
                                    clash + ifRef,
                            )
                    }
                }
            }
        }
        return SelectionCheck(problems.isEmpty(), problems)
    }

    /** For other parts of the module: the same check, with the set already read. */
    internal fun check(
        set: OptionSet,
        selection: Map<String, String>,
        allowRetired: Boolean = false,
    ) = check(set, rows(set.id), selection, allowRetired)
}

/** Rules keep the options they're about. */
@Component
internal class RulesUseOptions(
    private val jdbc: JdbcClient,
) : OptionUsage {
    override fun ensureUnused(optionId: UUID) {
        val used =
            jdbc
                .sql(
                    """
                    select count(*) from option_rule r where exists (
                      select 1 from option_choice c where c.option_id = :o and (c.id = r.if_choice or c.id = any(r.then_choices)))
                    """.trimIndent(),
                ).param("o", optionId)
                .query(Int::class.java)
                .single()
        if (used > 0) throw ConflictException("OPTION_IN_USE", "$used rule(s) are about this option: remove them first")
    }
}
