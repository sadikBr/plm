package com.plm.extensions.domain

import com.plm.extensions.api.ScriptLimits
import com.plm.extensions.api.ScriptPurpose
import com.plm.extensions.api.ScriptRun
import com.plm.extensions.api.ScriptSettings
import com.plm.extensions.api.ScriptTest
import com.plm.extensions.api.ServerScript
import com.plm.extensions.api.ServerScriptService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeService
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.NumberProvider
import com.plm.versioning.api.NumberRequest
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.Refusal
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private const val KEEP_RUNS = 200

@Service
@Transactional
internal class DefaultServerScriptService(
    private val jdbc: JdbcClient,
    private val types: TypeService,
    private val versioning: ObjectProvider<VersioningService>,
    transactions: PlatformTransactionManager,
    private val clock: Clock,
) : ServerScriptService {
    private val json = JsonMapper.builder().build()
    private val separately =
        TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    private fun strings(text: String?): List<String> =
        text?.let { json.readValue(it, Array<String>::class.java).toList() } ?: emptyList()

    private val mapper =
        org.springframework.jdbc.core.RowMapper { rs, _ ->
            ServerScript(
                rs.getObject("id", UUID::class.java),
                rs.getString("name"),
                ScriptPurpose.valueOf(rs.getString("purpose")),
                rs.getString("description"),
                rs.getString("source"),
                strings(rs.getString("types")),
                strings(rs.getString("operations")),
                rs.getBoolean("active"),
                rs.getInt("timeout_ms"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by"),
            )
        }

    /** Every script of the tenant; used on each guarded operation, so kept simple. */
    internal fun all(tenant: TenantId): List<ServerScript> =
        jdbc
            .sql("select * from server_script where tenant_id = :t order by name")
            .param("t", tenant.value)
            .query(mapper)
            .list()

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators manage server scripts",
            )
        }
    }

    private fun find(
        actor: Actor,
        id: UUID,
    ) = all(actor.tenant).firstOrNull { it.id == id } ?: throw NotFoundException("SCRIPT_NOT_FOUND", "No such script")

    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<ServerScript> {
        requireAdmin(actor)
        return all(actor.tenant)
    }

    private fun validate(
        actor: Actor,
        id: UUID?,
        s: ScriptSettings,
    ) {
        fun bad(message: String): Nothing = throw ValidationException("INVALID_SCRIPT", message)
        if (s.name.isBlank() || s.name.length > 100) bad("Name it (up to 100 characters)")
        if (s.timeoutMs !in
            10..ScriptLimits.MAX_TIMEOUT_MS
        ) {
            bad("The time limit is 10 to ${ScriptLimits.MAX_TIMEOUT_MS} ms")
        }
        if (s.purpose == ScriptPurpose.VALIDATION) {
            if (s.operations.isEmpty()) bad("Choose the operations it checks")
            s.operations.firstOrNull { GuardedOperation.of(it) == null }?.let { bad("Unknown operation $it") }
        }
        val known = types.list(actor).flatMap { listOf(it.name, it.id.value.toString()) }.toSet()
        s.types.firstOrNull { it !in known }?.let { bad("Unknown type $it") }
        ScriptSandbox.check(s.source, s.purpose.function)?.let { bad(it) }
        if (all(actor.tenant).any { it.name.equals(s.name.trim(), ignoreCase = true) && it.id != id }) {
            throw ConflictException("DUPLICATE_SCRIPT_NAME", "A script is already called ${s.name.trim()}")
        }
    }

    private fun save(
        actor: Actor,
        id: UUID,
        s: ScriptSettings,
        insert: Boolean,
    ): ServerScript {
        val sql =
            if (insert) {
                """
                insert into server_script (id, tenant_id, name, purpose, description, source, types, operations, active,
                    timeout_ms, updated_at, updated_by)
                values (:id, :t, :name, :purpose, :description, :source, cast(:types as jsonb), cast(:ops as jsonb), :active,
                    :timeout, :at, :by)
                """.trimIndent()
            } else {
                """
                update server_script set name = :name, purpose = :purpose, description = :description, source = :source,
                    types = cast(:types as jsonb), operations = cast(:ops as jsonb), active = :active, timeout_ms = :timeout,
                    updated_at = :at, updated_by = :by
                where id = :id and tenant_id = :t
                """.trimIndent()
            }
        jdbc
            .sql(sql)
            .param("id", id)
            .param("t", actor.tenant.value)
            .param("name", s.name.trim())
            .param("purpose", s.purpose.name)
            .param("description", s.description?.trim()?.ifEmpty { null })
            .param("source", s.source)
            .param("types", json.writeValueAsString(s.types))
            .param(
                "ops",
                json.writeValueAsString(
                    if (s.purpose ==
                        ScriptPurpose.VALIDATION
                    ) {
                        s.operations
                    } else {
                        emptyList()
                    },
                ),
            ).param("active", s.active)
            .param("timeout", s.timeoutMs)
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return find(actor, id)
    }

    override fun define(
        actor: Actor,
        settings: ScriptSettings,
    ): ServerScript {
        requireAdmin(actor)
        validate(actor, null, settings)
        return save(actor, UuidV7.generate(clock), settings, insert = true)
    }

    override fun update(
        actor: Actor,
        id: UUID,
        settings: ScriptSettings,
    ): ServerScript {
        requireAdmin(actor)
        find(actor, id)
        validate(actor, id, settings)
        return save(actor, id, settings, insert = false)
    }

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        find(actor, id)
        jdbc.sql("delete from server_script where id = :id").param("id", id).update()
    }

    @Transactional(readOnly = true)
    override fun runs(
        actor: Actor,
        id: UUID,
        limit: Int,
    ): List<ScriptRun> {
        requireAdmin(actor)
        find(actor, id)
        return jdbc
            .sql("select * from server_script_run where script_id = :id order by ran_at desc limit :limit")
            .param("id", id)
            .param("limit", limit.coerceIn(1, KEEP_RUNS))
            .query { rs, _ ->
                ScriptRun(
                    rs.getObject("id", UUID::class.java),
                    id,
                    rs.getTimestamp("ran_at").toInstant(),
                    rs.getString("actor"),
                    rs.getString("trigger"),
                    rs.getBoolean("ok"),
                    rs.getInt("duration_ms"),
                    rs.getLong("instructions"),
                    rs.getString("result")?.let { json.readValue(it, Any::class.java) },
                    rs.getString("error"),
                    strings(rs.getString("logs")),
                )
            }.list()
    }

    override fun test(
        actor: Actor,
        id: UUID?,
        test: ScriptTest,
    ): ScriptRun {
        requireAdmin(actor)
        val saved = id?.let { find(actor, it) }
        val source = test.source ?: saved?.source ?: throw ValidationException("INVALID_SCRIPT", "Nothing to run")
        val purpose = test.purpose ?: saved?.purpose ?: ScriptPurpose.AUTOMATION
        val r =
            ScriptSandbox.run(
                source,
                purpose.function,
                test.input,
                api(actor),
                saved?.timeoutMs ?: ScriptLimits.MAX_TIMEOUT_MS,
            )
        return ScriptRun(
            null,
            id,
            clock.instant(),
            actor.principal.value,
            "test",
            r.ok,
            r.durationMs,
            r.instructions,
            r.result,
            r.error,
            r.logs,
        )
    }

    override fun runAutomation(
        actor: Actor,
        id: UUID,
        input: Map<String, Any?>,
        trigger: String,
    ): ScriptRun {
        val script =
            all(actor.tenant).firstOrNull { it.id == id }
                ?: throw NotFoundException("SCRIPT_NOT_FOUND", "No such script")
        if (script.purpose !=
            ScriptPurpose.AUTOMATION
        ) {
            throw ValidationException("INVALID_SCRIPT", "${script.name} isn't an automation script")
        }
        return execute(actor, script, input, trigger)
    }

    /** Runs [script] with [input] as [actor] and records the run (even if the operation then rolls back). */
    internal fun execute(
        actor: Actor,
        script: ServerScript,
        input: Map<String, Any?>,
        trigger: String,
    ): ScriptRun {
        val r = ScriptSandbox.run(script.source, script.purpose.function, input, api(actor), script.timeoutMs)
        val run =
            ScriptRun(
                UuidV7.generate(clock),
                script.id,
                clock.instant(),
                actor.principal.value,
                trigger,
                r.ok,
                r.durationMs,
                r.instructions,
                r.result,
                r.error,
                r.logs,
            )
        separately.executeWithoutResult {
            jdbc
                .sql(
                    """
                    insert into server_script_run (id, tenant_id, script_id, ran_at, actor, trigger, ok, duration_ms, instructions,
                        result, error, logs)
                    values (:id, :t, :s, :at, :actor, :trigger, :ok, :ms, :n, cast(:result as jsonb), :error, cast(:logs as jsonb))
                    """.trimIndent(),
                ).param("id", run.id)
                .param("t", actor.tenant.value)
                .param("s", script.id)
                .param("at", Timestamp.from(run.ranAt))
                .param("actor", run.actor)
                .param("trigger", trigger)
                .param("ok", run.ok)
                .param("ms", run.durationMs)
                .param("n", run.instructions)
                .param("result", run.result?.let { json.writeValueAsString(it) })
                .param("error", run.error)
                .param("logs", json.writeValueAsString(run.logs))
                .update()
            jdbc
                .sql(
                    """
                    delete from server_script_run where script_id = :s and id not in
                        (select id from server_script_run where script_id = :s order by ran_at desc limit $KEEP_RUNS)
                    """.trimIndent(),
                ).param("s", script.id)
                .update()
        }
        return run
    }

    /** `plm.object` / `plm.find`: what [actor] may read, as plain data. */
    private fun api(actor: Actor) =
        object : ScriptApi {
            private fun view(o: VersionedObject) =
                mapOf(
                    "id" to o.id.value.toString(),
                    "number" to o.number,
                    "name" to o.name,
                    "typeId" to o.typeId.value.toString(),
                    "contextId" to o.contextId.value.toString(),
                    "version" to o.latest.version,
                    "state" to o.latest.state,
                    "owner" to o.owner,
                    "attributes" to o.latest.attributes,
                )

            override fun objectById(id: String): Any? {
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
                return versioning
                    .getObject()
                    .list(actor, ObjectQuery(ids = listOf(MasterId(uuid)), limit = 1))
                    .firstOrNull()
                    ?.let(::view)
            }

            override fun objectByNumber(number: String): Any? =
                versioning
                    .getObject()
                    .list(actor, ObjectQuery(text = number, limit = 20))
                    .firstOrNull { it.number == number }
                    ?.let(::view)
        }

    /** Whether [script] applies to an object whose type lineage holds [lineage] (names or ids). */
    internal fun appliesTo(
        script: ServerScript,
        lineage: Collection<String>,
    ) = script.types.isEmpty() || script.types.any { it in lineage }

    internal fun lineageOf(
        actor: Actor,
        typeId: com.plm.types.api.TypeId,
    ): List<String> = types.lineage(actor, typeId).flatMap { listOf(it.value.toString(), types.get(actor, it).name) }
}

/** Validation scripts (roadmap 5.14): each one that applies may refuse the operation. */
@Component
internal class ScriptValidation(
    private val scripts: DefaultServerScriptService,
) : OperationGuard {
    override fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal> {
        val applicable =
            scripts.all(actor.tenant).filter {
                it.active && it.purpose == ScriptPurpose.VALIDATION && check.operation.key in it.operations
            }
        if (applicable.isEmpty()) return emptyList()
        val lineage = scripts.lineageOf(actor, check.typeId)
        val ctx = check.model + mapOf("operation" to check.operation.key, "event" to check.event)
        return applicable.filter { scripts.appliesTo(it, lineage) }.flatMap { script ->
            val run = scripts.execute(actor, script, ctx, check.operation.key)
            when {
                // A script that can't run refuses: a broken check must not let everything through.
                !run.ok -> listOf(Refusal(script.name, "script", "${script.name} couldn't check this: ${run.error}"))
                run.result == null || run.result == false || run.result == "" -> emptyList()
                run.result is List<*> ->
                    run.result.filterNotNull().map {
                        Refusal(
                            script.name,
                            "script",
                            it.toString(),
                        )
                    }
                else -> listOf(Refusal(script.name, "script", run.result.toString()))
            }
        }
    }
}

/** Numbering scripts (roadmap 5.14): the first active one that applies and answers names the object. */
@Component
internal class ScriptNumbering(
    private val scripts: DefaultServerScriptService,
) : NumberProvider {
    override fun number(
        actor: Actor,
        request: NumberRequest,
    ): String? {
        val applicable = scripts.all(actor.tenant).filter { it.active && it.purpose == ScriptPurpose.NUMBERING }
        if (applicable.isEmpty()) return null
        val lineage = scripts.lineageOf(actor, request.typeId)
        val ctx =
            mapOf(
                "typeId" to request.typeId.value.toString(),
                "types" to lineage,
                "contextId" to request.contextId.toString(),
                "name" to request.name,
                "attributes" to request.attributes,
                "user" to actor.principal.value,
            )
        for (script in applicable.filter { scripts.appliesTo(it, lineage) }) {
            val run = scripts.execute(actor, script, ctx, "numbering")
            if (!run.ok) {
                throw ValidationException(
                    "NUMBERING_SCRIPT_FAILED",
                    "${script.name} couldn't number it: ${run.error}",
                )
            }
            val number = run.result?.toString()?.trim()
            if (!number.isNullOrEmpty()) return number
        }
        return null
    }
}
