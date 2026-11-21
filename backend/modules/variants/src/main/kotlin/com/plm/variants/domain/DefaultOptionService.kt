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
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.variants.api.Choice
import com.plm.variants.api.OptionDef
import com.plm.variants.api.OptionService
import com.plm.variants.api.OptionSet
import com.plm.variants.api.PartOptions
import com.plm.variants.api.SaveChoice
import com.plm.variants.api.SaveOption
import com.plm.variants.api.SaveOptionSet
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private val CODE = Regex("^[A-Z][A-Z0-9_]{0,29}$")

internal fun badOptions(message: String): Nothing = throw ValidationException("INVALID_OPTIONS", message)

@Service
@Transactional
internal class DefaultOptionService(
    private val jdbc: JdbcClient,
    private val access: AccessService,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val clock: Clock,
) : OptionService {
    internal data class SetRow(
        val id: UUID,
        val contextId: UUID,
        val name: String,
        val description: String?,
        val createdAt: java.time.Instant,
        val createdBy: String,
        val updatedAt: java.time.Instant,
    )

    private fun setRows(
        where: String,
        params: Map<String, Any?>,
    ): List<SetRow> =
        jdbc
            .sql("select * from option_set where $where order by name, id")
            .params(params)
            .query { rs, _ ->
                SetRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("context_id", UUID::class.java),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("created_by"),
                    rs.getTimestamp("updated_at").toInstant(),
                )
            }.list()

    /** The sets with their options and choices, in order. */
    internal fun assemble(rows: List<SetRow>): List<OptionSet> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }.toTypedArray()
        val choices =
            jdbc
                .sql(
                    """
                    select c.* from option_choice c join option_def o on o.id = c.option_id
                    where o.set_id = any(cast(:ids as uuid[])) order by c.position, c.code
                    """.trimIndent(),
                ).param("ids", ids)
                .query { rs, _ ->
                    rs.getObject("option_id", UUID::class.java) to
                        Choice(
                            rs.getObject("id", UUID::class.java),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getInt("position"),
                            rs.getBoolean("retired"),
                        )
                }.list()
                .groupBy({ it.first }, { it.second })
        val options =
            jdbc
                .sql("select * from option_def where set_id = any(cast(:ids as uuid[])) order by position, code")
                .param("ids", ids)
                .query { rs, _ ->
                    val id = rs.getObject("id", UUID::class.java)
                    rs.getObject("set_id", UUID::class.java) to
                        OptionDef(
                            id,
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getBoolean("required"),
                            rs.getInt("position"),
                            choices[id].orEmpty(),
                        )
                }.list()
                .groupBy({ it.first }, { it.second })
        val used =
            jdbc
                .sql(
                    "select set_id, count(*) from configurable_part where set_id = any(cast(:ids as uuid[])) group by set_id",
                ).param("ids", ids)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }
                .list()
                .toMap()
        return rows.map {
            OptionSet(
                it.id,
                it.contextId,
                it.name,
                it.description,
                options[it.id].orEmpty(),
                used[it.id] ?: 0,
                it.createdAt,
                it.createdBy,
                it.updatedAt,
            )
        }
    }

    private fun can(
        actor: Actor,
        contextId: UUID,
        permission: Permission,
    ) = access.has(actor, AccessTarget(ContextId(contextId)), permission)

    private fun row(
        actor: Actor,
        setId: UUID,
        permission: Permission = Permission.READ,
    ): SetRow {
        val r =
            setRows("id = :id and tenant_id = :t", mapOf("id" to setId, "t" to actor.tenant.value))
                .singleOrNull()
                ?.takeIf { can(actor, it.contextId, Permission.READ) }
                ?: throw NotFoundException("OPTION_SET_NOT_FOUND", "There's no such option set")
        if (permission != Permission.READ) access.require(actor, AccessTarget(ContextId(r.contextId)), permission)
        return r
    }

    @Transactional(readOnly = true)
    override fun sets(
        actor: Actor,
        contextId: UUID?,
    ): List<OptionSet> {
        val rows =
            setRows(
                "tenant_id = :t and (cast(:c as uuid) is null or context_id = :c)",
                mapOf("t" to actor.tenant.value, "c" to contextId),
            )
        val readable =
            rows
                .map { it.contextId }
                .distinct()
                .filter { can(actor, it, Permission.READ) }
                .toSet()
        return assemble(rows.filter { it.contextId in readable })
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        setId: UUID,
    ) = assemble(listOf(row(actor, setId))).single()

    private fun name(text: String): String {
        val n = text.trim()
        if (n.isEmpty() || n.length > 200) badOptions("A name is 1 to 200 characters")
        return n
    }

    private fun code(text: String): String {
        val c = text.trim().uppercase()
        if (!CODE.matches(
                c,
            )
        ) {
            badOptions("'$text' isn't a code: capitals, digits and _, starting with a letter, at most 30")
        }
        return c
    }

    private fun touch(setId: UUID) =
        jdbc
            .sql("update option_set set updated_at = :now where id = :id")
            .param("now", Timestamp.from(clock.instant()))
            .param("id", setId)
            .update()

    override fun create(
        actor: Actor,
        contextId: UUID,
        request: SaveOptionSet,
    ): OptionSet {
        access.require(actor, AccessTarget(ContextId(contextId)), Permission.MODIFY)
        val id = UuidV7.generate(clock)
        val now = Timestamp.from(clock.instant())
        jdbc
            .sql(
                """
                insert into option_set (id, tenant_id, context_id, name, description, created_at, created_by, updated_at)
                values (:id, :t, :c, :n, :d, :now, :by, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("t", actor.tenant.value)
            .param("c", contextId)
            .param("n", name(request.name))
            .param("d", request.description?.trim()?.takeIf { it.isNotEmpty() })
            .param("now", now)
            .param("by", actor.principal.value)
            .update()
        return get(actor, id)
    }

    override fun update(
        actor: Actor,
        setId: UUID,
        request: SaveOptionSet,
    ): OptionSet {
        row(actor, setId, Permission.MODIFY)
        jdbc
            .sql("update option_set set name = :n, description = :d where id = :id")
            .param("id", setId)
            .param("n", name(request.name))
            .param("d", request.description?.trim()?.takeIf { it.isNotEmpty() })
            .update()
        touch(setId)
        return get(actor, setId)
    }

    override fun delete(
        actor: Actor,
        setId: UUID,
    ) {
        val s = get(actor, setId)
        row(actor, setId, Permission.MODIFY)
        if (s.usedBy > 0) throw ConflictException("OPTION_SET_IN_USE", "${s.usedBy} configurable part(s) use it")
        jdbc.sql("delete from option_set where id = :id").param("id", setId).update()
    }

    private fun optionSetOf(optionId: UUID): UUID =
        jdbc
            .sql("select set_id from option_def where id = :id")
            .param("id", optionId)
            .query(UUID::class.java)
            .optional()
            .orElseThrow { NotFoundException("OPTION_NOT_FOUND", "There's no such option") }

    private fun duplicate(what: String): Nothing =
        throw ConflictException("DUPLICATE_CODE", "$what is already used here")

    override fun addOption(
        actor: Actor,
        setId: UUID,
        request: SaveOption,
    ): OptionSet {
        row(actor, setId, Permission.MODIFY)
        val c = code(request.code)
        val exists = get(actor, setId).options.any { it.code == c }
        if (exists) duplicate(c)
        jdbc
            .sql(
                """
                insert into option_def (id, set_id, code, name, required, position)
                values (:id, :s, :c, :n, :r, coalesce((select max(position) from option_def where set_id = :s), 0) + 10)
                """.trimIndent(),
            ).param("id", UuidV7.generate(clock))
            .param("s", setId)
            .param("c", c)
            .param("n", name(request.name))
            .param("r", request.required)
            .update()
        touch(setId)
        return get(actor, setId)
    }

    override fun updateOption(
        actor: Actor,
        optionId: UUID,
        request: SaveOption,
    ): OptionSet {
        val setId = optionSetOf(optionId)
        row(actor, setId, Permission.MODIFY)
        val c = code(request.code)
        if (get(actor, setId).options.any { it.code == c && it.id != optionId }) duplicate(c)
        jdbc
            .sql("update option_def set code = :c, name = :n, required = :r where id = :id")
            .param("id", optionId)
            .param("c", c)
            .param("n", name(request.name))
            .param("r", request.required)
            .update()
        touch(setId)
        return get(actor, setId)
    }

    override fun removeOption(
        actor: Actor,
        optionId: UUID,
    ): OptionSet {
        val setId = optionSetOf(optionId)
        row(actor, setId, Permission.MODIFY)
        usage.ensureUnused(optionId)
        jdbc.sql("delete from option_def where id = :id").param("id", optionId).update()
        touch(setId)
        return get(actor, setId)
    }

    override fun addChoice(
        actor: Actor,
        optionId: UUID,
        request: SaveChoice,
    ): OptionSet {
        val setId = optionSetOf(optionId)
        row(actor, setId, Permission.MODIFY)
        val c = code(request.code)
        val option = get(actor, setId).options.single { it.id == optionId }
        if (option.choices.any { it.code == c }) duplicate("${option.code}=$c")
        jdbc
            .sql(
                """
                insert into option_choice (id, option_id, code, name, position)
                values (:id, :o, :c, :n, coalesce((select max(position) from option_choice where option_id = :o), 0) + 10)
                """.trimIndent(),
            ).param("id", UuidV7.generate(clock))
            .param("o", optionId)
            .param("c", c)
            .param("n", name(request.name))
            .update()
        touch(setId)
        return get(actor, setId)
    }

    override fun updateChoice(
        actor: Actor,
        choiceId: UUID,
        request: SaveChoice,
    ): OptionSet {
        val optionId =
            jdbc
                .sql("select option_id from option_choice where id = :id")
                .param("id", choiceId)
                .query(UUID::class.java)
                .optional()
                .orElseThrow { NotFoundException("CHOICE_NOT_FOUND", "There's no such choice") }
        val setId = optionSetOf(optionId)
        row(actor, setId, Permission.MODIFY)
        val c = code(request.code)
        val option = get(actor, setId).options.single { it.id == optionId }
        if (option.choices.any { it.code == c && it.id != choiceId }) duplicate("${option.code}=$c")
        jdbc
            .sql("update option_choice set code = :c, name = :n, retired = :r where id = :id")
            .param("id", choiceId)
            .param("c", c)
            .param("n", name(request.name))
            .param("r", request.retired)
            .update()
        touch(setId)
        return get(actor, setId)
    }

    /** Where options are used (conditions, rules): checked before an option goes. */
    internal var usage: OptionUsage = OptionUsage { }

    private fun part(
        actor: Actor,
        partId: UUID,
    ) = versioning.get(actor, MasterId(partId)).also {
        if (TypeId.PART !in
            types.lineage(actor, it.typeId)
        ) {
            badOptions("${it.number} isn't a part: only parts are configurable")
        }
    }

    internal fun setOf(partId: UUID): UUID? =
        jdbc
            .sql("select set_id from configurable_part where master_id = :m")
            .param("m", partId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    @Transactional(readOnly = true)
    override fun partOptions(
        actor: Actor,
        partId: UUID,
    ): PartOptions {
        part(actor, partId)
        val set = setOf(partId)?.let { id -> runCatching { get(actor, id) }.getOrNull() }
        return PartOptions(partId, set)
    }

    override fun assign(
        actor: Actor,
        partId: UUID,
        setId: UUID?,
    ): PartOptions {
        val o = part(actor, partId)
        access.require(actor, AccessTarget(o.contextId, o.typeId, o.latest.state, o.id.value), Permission.MODIFY)
        if (setId == null) {
            jdbc.sql("delete from configurable_part where master_id = :m").param("m", partId).update()
        } else {
            row(actor, setId)
            jdbc
                .sql(
                    """
                    insert into configurable_part (master_id, tenant_id, set_id) values (:m, :t, :s)
                    on conflict (master_id) do update set set_id = excluded.set_id
                    """.trimIndent(),
                ).param("m", partId)
                .param("t", actor.tenant.value)
                .param("s", setId)
                .update()
        }
        return partOptions(actor, partId)
    }
}

/** Refuses removing an option that conditions or rules still use (filled in by 7.6, 7.7). */
internal fun interface OptionUsage {
    fun ensureUnused(optionId: UUID)
}
