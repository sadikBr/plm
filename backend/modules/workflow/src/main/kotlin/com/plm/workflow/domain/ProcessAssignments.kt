package com.plm.workflow.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.Permission
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.ProcessAssignment
import com.plm.workflow.api.ProcessAssignmentService
import com.plm.workflow.api.ProcessStatus
import com.plm.workflow.api.ProcessTemplateService
import com.plm.workflow.api.ProcessTrigger
import com.plm.workflow.api.SaveProcessAssignment
import com.plm.workflow.api.StartProcess
import com.plm.workflow.infra.ProcessRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private val log = LoggerFactory.getLogger(ProcessAssignmentListener::class.java)

internal data class AssignmentRow(
    val id: UUID,
    val contextId: UUID,
    val typeId: UUID,
    val trigger: ProcessTrigger,
    val state: String?,
    val templateId: UUID,
    val active: Boolean,
)

@Service
@Transactional
internal class DefaultProcessAssignmentService(
    private val jdbc: JdbcClient,
    private val contexts: ContextService,
    private val access: AccessService,
    private val types: TypeService,
    private val templates: ProcessTemplateService,
    private val versioning: VersioningService,
    private val clock: Clock,
) : ProcessAssignmentService {
    private fun rows(
        tenant: TenantId,
        contextIds: Collection<UUID>?,
    ): List<AssignmentRow> =
        jdbc
            .sql(
                """
                select * from process_assignment where tenant_id = :tenant
                  and (cast(:all as boolean) or context_id in (:ids))
                order by created_at
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("all", contextIds == null)
            .param("ids", contextIds?.ifEmpty { listOf(UUID(0, 0)) } ?: listOf(UUID(0, 0)))
            .query { rs, _ ->
                AssignmentRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("context_id", UUID::class.java),
                    rs.getObject("type_id", UUID::class.java),
                    ProcessTrigger.valueOf(rs.getString("trigger")),
                    rs.getString("state"),
                    rs.getObject("template_id", UUID::class.java),
                    rs.getBoolean("active"),
                )
            }.list()

    private fun view(
        actor: Actor,
        row: AssignmentRow,
        inherited: Boolean = false,
    ): ProcessAssignment {
        val template = templates.list(actor).firstOrNull { it.id == row.templateId }
        return ProcessAssignment(
            id = row.id,
            contextId = row.contextId,
            contextName = contexts.get(actor, ContextId(row.contextId)).name,
            typeId = row.typeId,
            trigger = row.trigger,
            state = row.state,
            templateId = row.templateId,
            templateName = template?.name ?: "?",
            active = row.active,
            inherited = inherited,
        )
    }

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        contextId: UUID?,
    ): List<ProcessAssignment> {
        val visible =
            if (contextId != null) {
                listOf(contexts.get(actor, ContextId(contextId)).id.value)
            } else if (actor.siteAdmin) {
                null
            } else {
                throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators list every assignment")
            }
        return rows(actor.tenant, visible).map { view(actor, it) }
    }

    private fun check(
        actor: Actor,
        request: SaveProcessAssignment,
    ) {
        val context = contexts.get(actor, ContextId(request.contextId))
        if (context.parentId == null) {
            if (!actor.siteAdmin) {
                throw ForbiddenException(
                    "ADMIN_REQUIRED",
                    "Only site administrators assign processes site-wide",
                )
            }
        } else {
            access.require(actor, AccessTarget(context.id), Permission.MANAGE)
        }
        types.get(actor, TypeId(request.typeId))
        templates.get(actor, request.templateId)
        if ((request.trigger == ProcessTrigger.ON_STATE) != !request.state.isNullOrBlank()) {
            throw ValidationException(
                "INVALID_ASSIGNMENT",
                "A state is given exactly when the process starts on entering it",
            )
        }
    }

    private fun one(
        actor: Actor,
        id: UUID,
    ) = rows(actor.tenant, null).firstOrNull { it.id == id }
        ?: throw NotFoundException("ASSIGNMENT_NOT_FOUND", "There's no such process assignment")

    override fun create(
        actor: Actor,
        request: SaveProcessAssignment,
    ): ProcessAssignment {
        check(actor, request)
        val id = UuidV7.generate(clock)
        jdbc
            .sql(
                """
                insert into process_assignment (id, tenant_id, context_id, type_id, trigger, state, template_id, active,
                    created_at, created_by)
                values (:id, :tenant, :context, :type, :trigger, :state, :template, :active, :now, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", actor.tenant.value)
            .param("context", request.contextId)
            .param("type", request.typeId)
            .param("trigger", request.trigger.name)
            .param("state", request.state?.trim()?.ifEmpty { null })
            .param("template", request.templateId)
            .param("active", request.active)
            .param("now", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return view(actor, one(actor, id))
    }

    override fun update(
        actor: Actor,
        id: UUID,
        request: SaveProcessAssignment,
    ): ProcessAssignment {
        val before = one(actor, id)
        check(actor, request.copy(contextId = before.contextId))
        jdbc
            .sql(
                """
                update process_assignment set type_id = :type, trigger = :trigger, state = :state,
                    template_id = :template, active = :active
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .param("type", request.typeId)
            .param("trigger", request.trigger.name)
            .param("state", request.state?.trim()?.ifEmpty { null })
            .param("template", request.templateId)
            .param("active", request.active)
            .update()
        return view(actor, one(actor, id))
    }

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        val row = one(actor, id)
        check(actor, SaveProcessAssignment(row.contextId, row.typeId, row.trigger, row.state, row.templateId))
        jdbc.sql("delete from process_assignment where id = :id").param("id", id).update()
    }

    /** The assignments in effect for an object of [typeId] in [contextId]: nearest context, then most specific type. */
    fun effective(
        actor: Actor,
        typeId: TypeId,
        contextId: ContextId,
    ): List<Pair<AssignmentRow, Boolean>> {
        val path = contexts.path(actor, contextId).map { it.id.value }.reversed() // nearest first
        val lineage = types.lineage(actor, typeId).map { it.value }.reversed() // most specific first
        val candidates = rows(actor.tenant, path).filter { it.active && it.typeId in lineage }
        return candidates
            .sortedWith(compareBy({ path.indexOf(it.contextId) }, { lineage.indexOf(it.typeId) }))
            // One automatic process per trigger (and state); every template offered by hand is offered.
            .distinctBy {
                if (it.trigger ==
                    ProcessTrigger.MANUAL
                ) {
                    it.templateId.toString()
                } else {
                    "${it.trigger}/${it.state}"
                }
            }.map { it to (it.contextId != contextId.value || it.typeId != typeId.value) }
    }

    @Transactional(readOnly = true)
    override fun forObject(
        actor: Actor,
        objectId: UUID,
    ): List<ProcessAssignment> {
        val o = versioning.get(actor, MasterId(objectId))
        return effective(actor, o.typeId, o.contextId).map { (r, inherited) -> view(actor, r, inherited) }
    }
}

/**
 * Starts assigned processes (roadmap 5.5): on creation, and when an object enters a state, unless
 * a process moved it there (a process's own steps never start another).
 */
@Component
internal class ProcessAssignmentListener(
    private val assignments: DefaultProcessAssignmentService,
    private val processes: com.plm.workflow.api.ProcessService,
    private val repository: ProcessRepository,
    private val versioning: VersioningService,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        val trigger =
            when (envelope.eventType) {
                "versioning.ObjectCreated.v1" -> ProcessTrigger.ON_CREATE
                "versioning.ObjectStateChanged.v1" -> ProcessTrigger.ON_STATE
                else -> return
            }
        if (envelope.actor == PROCESS_ACTOR) return
        val tenant = TenantId(envelope.tenantId)
        // The process acts for whoever triggered it; reading as administrator, since they could anyway.
        val actor = Actor(tenant, PrincipalId(envelope.actor), siteAdmin = true)
        val objectId = UUID.fromString(envelope.payload["objectId"].toString())
        val o = versioning.list(actor, ObjectQuery(ids = listOf(MasterId(objectId)), limit = 1)).firstOrNull() ?: return
        val state = envelope.payload["to"]?.toString() ?: o.latest.state
        val due =
            assignments
                .effective(actor, o.typeId, o.contextId)
                .map { it.first }
                .filter { it.trigger == trigger && (trigger != ProcessTrigger.ON_STATE || it.state == state) }
        if (due.isEmpty()) return
        val running = repository.instances(tenant, objectId, ProcessStatus.RUNNING).map { it.templateId }.toSet()
        // Never fail the change that triggered it: only active templates, not already running.
        val active =
            repository
                .templates(tenant)
                .filter { it.active }
                .map { it.id }
                .toSet()
        for (a in due.filter { it.templateId !in running && it.templateId in active }) {
            log.info("Starting assigned process {} on {} ({})", a.templateId, o.number, trigger)
            processes.start(
                actor,
                StartProcess(a.templateId, objectId, "Started automatically (${trigger.name.lowercase()})"),
            )
        }
    }
}
