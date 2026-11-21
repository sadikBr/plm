package com.plm.automation.domain

import com.plm.automation.api.Automation
import com.plm.automation.api.AutomationAction
import com.plm.automation.api.AutomationActionKind
import com.plm.automation.api.AutomationRun
import com.plm.automation.api.AutomationService
import com.plm.automation.api.AutomationSettings
import com.plm.automation.api.AutomationStep
import com.plm.automation.api.AutomationTrigger
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.expression.ExpressionKind
import com.plm.platform.expression.Expressions
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

internal const val AUTOMATION_ACTOR = "automation"
private const val KEEP_RUNS = 200

internal val TRIGGERS =
    listOf(
        AutomationTrigger(
            "versioning.ObjectCreated.v1",
            "An object is created",
            listOf("number", "name", "typeId", "contextId"),
        ),
        AutomationTrigger(
            "versioning.ObjectStateChanged.v1",
            "An object changes state",
            listOf("from", "to", "comment", "override"),
        ),
        AutomationTrigger(
            "versioning.ObjectCheckedIn.v1",
            "An object is checked in",
            listOf("number", "version", "comment"),
        ),
        AutomationTrigger("versioning.ObjectRevised.v1", "An object is revised", listOf("number", "version")),
        AutomationTrigger(
            "versioning.ObjectRenamed.v1",
            "An object is renamed",
            listOf("previousNumber", "previousName", "name"),
        ),
        AutomationTrigger("versioning.ObjectMoved.v1", "An object is moved", listOf("fromContextId", "contextId")),
        AutomationTrigger(
            "workflow.ProcessEnded.v1",
            "A process ends",
            listOf("name", "status", "outcome", "subjectId"),
        ),
        AutomationTrigger("workflow.SignatureRecorded.v1", "An approval is signed", listOf("meaning", "outcome")),
        AutomationTrigger("change.PromotionDecided.v1", "A promotion request is decided", listOf("number", "status")),
    )

@Service
@Transactional
internal class DefaultAutomationService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : AutomationService {
    internal val json: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val mapper =
        org.springframework.jdbc.core.RowMapper { rs, _ ->
            Automation(
                rs.getObject("id", UUID::class.java),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("active"),
                rs.getString("event"),
                json.readValue(rs.getString("types"), Array<String>::class.java).toList(),
                rs.getString("condition"),
                json.readValue(rs.getString("actions"), Array<AutomationAction>::class.java).toList(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by"),
            )
        }

    internal fun all(tenant: TenantId): List<Automation> =
        jdbc
            .sql("select * from automation where tenant_id = :t order by name")
            .param("t", tenant.value)
            .query(mapper)
            .list()

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators manage automations")
    }

    private fun find(
        actor: Actor,
        id: UUID,
    ) = all(actor.tenant).firstOrNull { it.id == id }
        ?: throw NotFoundException("AUTOMATION_NOT_FOUND", "No such automation")

    @Transactional(readOnly = true)
    override fun triggers(actor: Actor) = TRIGGERS

    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<Automation> {
        requireAdmin(actor)
        return all(actor.tenant)
    }

    private fun validate(
        actor: Actor,
        id: UUID?,
        s: AutomationSettings,
    ) {
        fun bad(message: String): Nothing = throw ValidationException("INVALID_AUTOMATION", message)
        if (s.name.isBlank() || s.name.length > 100) bad("Name it (up to 100 characters)")
        if (TRIGGERS.none { it.event == s.event }) bad("Unknown event ${s.event}")
        s.condition?.takeIf { it.isNotBlank() }?.let { c ->
            Expressions.check(c, ExpressionKind.CONDITION).firstOrNull()?.let { bad("Condition: ${it.message}") }
        }
        if (s.actions.isEmpty()) bad("Add at least one action")
        if (s.actions.size > 10) bad("At most 10 actions")
        s.actions.forEachIndexed { i, a ->
            val at = "Action ${i + 1}"
            when (a.kind) {
                AutomationActionKind.SET_STATE -> if (a.state.isNullOrBlank()) bad("$at: which state?")
                AutomationActionKind.SET_ATTRIBUTE -> if (a.attribute.isNullOrBlank()) bad("$at: which attribute?")
                AutomationActionKind.NOTIFY -> {
                    if (a.recipients.isEmpty()) bad("$at: tell whom?")
                    a.recipients.firstOrNull { it != "owner" && it != "actor" && !it.startsWith("user:") }?.let {
                        bad("$at: $it isn't owner, actor or user:<username>")
                    }
                    if (a.message.isNullOrBlank()) bad("$at: what message?")
                }
                AutomationActionKind.START_PROCESS -> if (a.templateId == null) bad("$at: which process?")
                AutomationActionKind.WEBHOOK ->
                    if (a.url.isNullOrBlank() || !(a.url.startsWith("https://") || a.url.startsWith("http://"))) {
                        bad("$at: the URL must start with https://")
                    }
                AutomationActionKind.RUN_SCRIPT -> if (a.scriptId == null) bad("$at: which script?")
            }
        }
        if (all(actor.tenant).any { it.name.equals(s.name.trim(), ignoreCase = true) && it.id != id }) {
            throw ConflictException("DUPLICATE_AUTOMATION_NAME", "An automation is already called ${s.name.trim()}")
        }
    }

    private fun save(
        actor: Actor,
        id: UUID,
        s: AutomationSettings,
        insert: Boolean,
    ): Automation {
        val sql =
            if (insert) {
                """
                insert into automation (id, tenant_id, name, description, active, event, types, condition, actions, updated_at, updated_by)
                values (:id, :t, :name, :description, :active, :event, cast(:types as jsonb), :condition, cast(:actions as jsonb), :at, :by)
                """.trimIndent()
            } else {
                """
                update automation set name = :name, description = :description, active = :active, event = :event,
                    types = cast(:types as jsonb), condition = :condition, actions = cast(:actions as jsonb), updated_at = :at, updated_by = :by
                where id = :id and tenant_id = :t
                """.trimIndent()
            }
        jdbc
            .sql(sql)
            .param("id", id)
            .param("t", actor.tenant.value)
            .param("name", s.name.trim())
            .param("description", s.description?.trim()?.ifEmpty { null })
            .param("active", s.active)
            .param("event", s.event)
            .param("types", json.writeValueAsString(s.types))
            .param("condition", s.condition?.trim()?.ifEmpty { null })
            .param("actions", json.writeValueAsString(s.actions))
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return find(actor, id)
    }

    override fun define(
        actor: Actor,
        settings: AutomationSettings,
    ): Automation {
        requireAdmin(actor)
        validate(actor, null, settings)
        return save(actor, UuidV7.generate(clock), settings, insert = true)
    }

    override fun update(
        actor: Actor,
        id: UUID,
        settings: AutomationSettings,
    ): Automation {
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
        jdbc.sql("delete from automation where id = :id").param("id", id).update()
    }

    @Transactional(readOnly = true)
    override fun runs(
        actor: Actor,
        id: UUID,
        limit: Int,
    ): List<AutomationRun> {
        requireAdmin(actor)
        find(actor, id)
        return jdbc
            .sql("select * from automation_run where automation_id = :id order by ran_at desc limit :limit")
            .param("id", id)
            .param("limit", limit.coerceIn(1, KEEP_RUNS))
            .query { rs, _ ->
                AutomationRun(
                    rs.getObject("id", UUID::class.java),
                    id,
                    rs.getTimestamp("ran_at").toInstant(),
                    rs.getString("event_type"),
                    rs.getObject("object_id", UUID::class.java),
                    rs.getString("object_number"),
                    rs.getString("outcome"),
                    json.readValue(rs.getString("steps"), Array<AutomationStep>::class.java).toList(),
                    rs.getInt("duration_ms"),
                )
            }.list()
    }

    internal fun record(
        tenant: TenantId,
        run: AutomationRun,
        eventId: UUID,
    ) {
        jdbc
            .sql(
                """
                insert into automation_run (id, tenant_id, automation_id, ran_at, event_id, event_type, object_id, object_number,
                    outcome, steps, duration_ms)
                values (:id, :t, :a, :at, :e, :type, :o, :n, :outcome, cast(:steps as jsonb), :ms)
                """.trimIndent(),
            ).param("id", run.id)
            .param("t", tenant.value)
            .param("a", run.automationId)
            .param("at", Timestamp.from(run.ranAt))
            .param("e", eventId)
            .param("type", run.eventType)
            .param("o", run.objectId)
            .param("n", run.objectNumber)
            .param("outcome", run.outcome)
            .param("steps", json.writeValueAsString(run.steps))
            .param("ms", run.durationMs)
            .update()
        jdbc
            .sql(
                """
                delete from automation_run where automation_id = :a and id not in
                    (select id from automation_run where automation_id = :a order by ran_at desc limit $KEEP_RUNS)
                """.trimIndent(),
            ).param("a", run.automationId)
            .update()
    }
}
