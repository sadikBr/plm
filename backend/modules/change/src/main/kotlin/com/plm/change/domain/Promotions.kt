package com.plm.change.domain

import com.plm.change.api.CreatePromotion
import com.plm.change.api.PromotionDecided
import com.plm.change.api.PromotionItem
import com.plm.change.api.PromotionRequest
import com.plm.change.api.PromotionService
import com.plm.change.api.PromotionStatus
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.Refusal
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.ProcessService
import com.plm.workflow.api.StartProcess
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private val log = LoggerFactory.getLogger(PromotionDecisions::class.java)

/** Who changes objects on a process's behalf: never stopped by the locks processes hold. */
internal const val PROCESS_ACTOR = "process"

internal val PROMOTION_REVIEW: UUID = UUID.fromString("00000000-0000-7000-8000-000000000502")

internal data class RequestRow(
    val id: UUID,
    val tenantId: UUID,
    val number: String,
    val name: String,
    val targetState: String,
    val contextId: UUID,
    val status: PromotionStatus,
    val comment: String?,
    val instanceId: UUID?,
    val createdBy: String,
    val createdAt: java.time.Instant,
    val decidedAt: java.time.Instant?,
)

internal data class ItemRow(
    val requestId: UUID,
    val objectId: UUID,
    val iterationId: UUID,
    val version: String,
    val stateBefore: String,
)

@Component
internal class PromotionRepository(
    private val jdbc: JdbcClient,
) {
    private val requestMapper =
        org.springframework.jdbc.core.RowMapper { rs, _ ->
            RequestRow(
                rs.getObject("id", UUID::class.java),
                rs.getObject("tenant_id", UUID::class.java),
                rs.getString("number"),
                rs.getString("name"),
                rs.getString("target_state"),
                rs.getObject("context_id", UUID::class.java),
                PromotionStatus.valueOf(rs.getString("status")),
                rs.getString("comment"),
                rs.getObject("instance_id", UUID::class.java),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("decided_at")?.toInstant(),
            )
        }

    fun nextNumber(): String =
        "PRM-%05d".format(jdbc.sql("select nextval('promotion_number_seq')").query(Long::class.java).single())

    fun requests(
        tenant: TenantId,
        status: PromotionStatus? = null,
        objectId: UUID? = null,
        instanceId: UUID? = null,
    ): List<RequestRow> =
        jdbc
            .sql(
                """
                select r.* from promotion_request r where r.tenant_id = :tenant
                  and (cast(:status as text) is null or r.status = :status)
                  and (cast(:instance as uuid) is null or r.instance_id = :instance)
                  and (cast(:object as uuid) is null or exists (
                      select 1 from promotion_item i where i.request_id = r.id and i.object_id = :object))
                order by r.created_at desc limit 500
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("status", status?.name)
            .param("instance", instanceId)
            .param("object", objectId)
            .query(requestMapper)
            .list()

    fun request(id: UUID): RequestRow? =
        jdbc
            .sql(
                "select * from promotion_request where id = :id",
            ).param("id", id)
            .query(requestMapper)
            .optional()
            .orElse(null)

    fun items(requestId: UUID): List<ItemRow> =
        jdbc
            .sql("select * from promotion_item where request_id = :id order by version")
            .param("id", requestId)
            .query { rs, _ ->
                ItemRow(
                    requestId,
                    rs.getObject("object_id", UUID::class.java),
                    rs.getObject("iteration_id", UUID::class.java),
                    rs.getString("version"),
                    rs.getString("state_before"),
                )
            }.list()

    fun insert(
        r: RequestRow,
        items: List<ItemRow>,
    ) {
        jdbc
            .sql(
                """
                insert into promotion_request (id, tenant_id, number, name, target_state, context_id, status, comment,
                    created_by, created_at)
                values (:id, :tenant, :number, :name, :target, :context, :status, :comment, :by, :at)
                """.trimIndent(),
            ).param("id", r.id)
            .param("tenant", r.tenantId)
            .param("number", r.number)
            .param("name", r.name)
            .param("target", r.targetState)
            .param("context", r.contextId)
            .param("status", r.status.name)
            .param("comment", r.comment)
            .param("by", r.createdBy)
            .param("at", Timestamp.from(r.createdAt))
            .update()
        items.forEach {
            jdbc
                .sql(
                    """
                    insert into promotion_item (request_id, object_id, iteration_id, version, state_before)
                    values (:r, :o, :i, :v, :s)
                    """.trimIndent(),
                ).param("r", r.id)
                .param("o", it.objectId)
                .param("i", it.iterationId)
                .param("v", it.version)
                .param("s", it.stateBefore)
                .update()
        }
    }

    fun setInstance(
        id: UUID,
        instanceId: UUID,
    ) {
        jdbc
            .sql(
                "update promotion_request set instance_id = :i where id = :id",
            ).param("id", id)
            .param("i", instanceId)
            .update()
    }

    fun decide(
        id: UUID,
        status: PromotionStatus,
        at: java.time.Instant,
    ): Boolean =
        jdbc
            .sql(
                "update promotion_request set status = :s, decided_at = :at where id = :id and status = 'UNDER_REVIEW'",
            ).param("id", id)
            .param("s", status.name)
            .param("at", Timestamp.from(at))
            .update() == 1

    /** The open request [objectId] is locked by, if any. */
    fun lockOf(objectId: UUID): String? =
        jdbc
            .sql(
                """
                select r.number from promotion_request r join promotion_item i on i.request_id = r.id
                where i.object_id = :o and r.status = 'UNDER_REVIEW' limit 1
                """.trimIndent(),
            ).param("o", objectId)
            .query(String::class.java)
            .optional()
            .orElse(null)
}

@Service
@Transactional
internal class DefaultPromotionService(
    private val repository: PromotionRepository,
    private val versioning: VersioningService,
    private val processes: ProcessService,
    private val events: DomainEvents,
    private val clock: Clock,
) : PromotionService {
    private fun view(
        actor: Actor,
        r: RequestRow,
    ): PromotionRequest {
        val items = repository.items(r.id)
        val objects =
            versioning
                .list(actor, ObjectQuery(ids = items.map { MasterId(it.objectId) }, limit = 500))
                .associateBy { it.id.value }
        return PromotionRequest(
            id = r.id,
            number = r.number,
            name = r.name,
            targetState = r.targetState,
            contextId = r.contextId,
            status = r.status,
            comment = r.comment,
            instanceId = r.instanceId,
            createdBy = r.createdBy,
            createdAt = r.createdAt,
            decidedAt = r.decidedAt,
            items =
                items.map {
                    val o = objects[it.objectId]
                    PromotionItem(
                        it.objectId,
                        o?.number,
                        o?.name,
                        o?.typeId?.value,
                        it.version,
                        it.stateBefore,
                        o?.latest?.state,
                    )
                },
        )
    }

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        status: PromotionStatus?,
    ) = repository.requests(actor.tenant, status).map { view(actor, it) }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: UUID,
    ): PromotionRequest {
        val r =
            repository.request(id)?.takeIf { it.tenantId == actor.tenant.value }
                ?: throw NotFoundException("PROMOTION_NOT_FOUND", "There's no such promotion request")
        return view(actor, r)
    }

    @Transactional(readOnly = true)
    override fun forObject(
        actor: Actor,
        objectId: UUID,
    ): List<PromotionRequest> {
        versioning.get(actor, MasterId(objectId))
        return repository.requests(actor.tenant, objectId = objectId).map { view(actor, it) }
    }

    override fun create(
        actor: Actor,
        request: CreatePromotion,
    ): PromotionRequest {
        val name = request.name.trim()

        fun bad(message: String): Nothing = throw ValidationException("INVALID_PROMOTION", message)
        if (name.isEmpty() || name.length > 200) bad("Give the request a name")
        val ids = request.objectIds.distinct()
        if (ids.isEmpty()) bad("Choose the objects to promote")
        if (ids.size > 500) bad("At most 500 objects at once")
        val objects = ids.map { versioning.get(actor, MasterId(it)) }
        val problems = mutableListOf<String>()
        for (o in objects) {
            if (o.checkedOutBy != null) problems += "${o.number} is checked out"
            repository.lockOf(o.id.value)?.let { problems += "${o.number} is already in $it" }
            if (o.latest.state == request.targetState) problems += "${o.number} is already ${request.targetState}"
            val states =
                versioning
                    .lifecycle(actor, o.id)
                    .template.states
                    .map { it.key }
            if (request.targetState !in
                states
            ) {
                problems += "${o.number}'s lifecycle has no state ${request.targetState}"
            }
        }
        if (problems.isNotEmpty()) {
            throw ValidationException("INVALID_PROMOTION", problems.joinToString("; "), mapOf("errors" to problems))
        }
        val row =
            RequestRow(
                id = UuidV7.generate(clock),
                tenantId = actor.tenant.value,
                number = repository.nextNumber(),
                name = name,
                targetState = request.targetState,
                contextId = objects.first().contextId.value,
                status = PromotionStatus.UNDER_REVIEW,
                comment = request.comment?.trim()?.ifEmpty { null },
                instanceId = null,
                createdBy = actor.principal.value,
                createdAt = clock.instant(),
                decidedAt = null,
            )
        repository.insert(
            row,
            objects.map {
                ItemRow(row.id, it.id.value, it.latest.id.value, it.latest.version, it.latest.state)
            },
        )
        val run =
            processes.start(
                actor,
                StartProcess(
                    request.templateId ?: PROMOTION_REVIEW,
                    comment = row.comment ?: name,
                    contextId = row.contextId,
                    about = "${row.number} · ${objects.size} object${if (objects.size == 1) "" else "s"} to ${row.targetState}",
                    link = "/promotions/${row.id}",
                ),
            )
        repository.setInstance(row.id, run.id)
        return get(actor, row.id)
    }

    override fun cancel(
        actor: Actor,
        id: UUID,
    ): PromotionRequest {
        val r = get(actor, id)
        if (r.createdBy != actor.principal.value && !actor.siteAdmin) {
            throw ForbiddenException("ACCESS_DENIED", "Only whoever asked, or an administrator, can cancel it")
        }
        if (r.status !=
            PromotionStatus.UNDER_REVIEW
        ) {
            throw ConflictException("PROMOTION_DECIDED", "It has already been decided")
        }
        repository.decide(r.id, PromotionStatus.CANCELLED, clock.instant())
        r.instanceId?.let { processes.cancel(actor, it, "Promotion request cancelled") }
        events.publish(actor, PromotionDecided(r.id, r.number, PromotionStatus.CANCELLED, r.items.map { it.objectId }))
        return get(actor, id)
    }
}

/** When a request's review ends: approval promotes its objects (roadmap 5.7). */
@Component
internal class PromotionDecisions(
    private val repository: PromotionRepository,
    private val versioning: VersioningService,
    private val events: DomainEvents,
    private val clock: Clock,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "workflow.ProcessEnded.v1") return
        val instance = UUID.fromString(envelope.payload["instanceId"].toString())
        val tenant = TenantId(envelope.tenantId)
        val r = repository.requests(tenant, PromotionStatus.UNDER_REVIEW, instanceId = instance).firstOrNull() ?: return
        val approved = envelope.payload["status"] == "COMPLETED" && envelope.payload["outcome"] == "APPROVED"
        val status = if (approved) PromotionStatus.APPROVED else PromotionStatus.REJECTED
        if (!repository.decide(r.id, status, clock.instant())) return
        val items = repository.items(r.id)
        val system = Actor(tenant, PrincipalId(PROCESS_ACTOR), siteAdmin = true)
        if (approved) {
            for (item in items) {
                // The approval is the authority: along the lifecycle where it can, overriding where it can't.
                val o = versioning.get(system, MasterId(item.objectId))
                if (o.latest.state == r.targetState) continue
                val allowed = versioning.lifecycle(system, o.id).nextStates.contains(r.targetState)
                ReleaseAuthority.by(r.number) {
                    versioning.setState(
                        system,
                        o.id,
                        SetState(r.targetState, "Promotion request ${r.number} approved", override = !allowed),
                    )
                }
            }
            log.info("Promotion request {} approved: {} objects to {}", r.number, items.size, r.targetState)
        }
        events.publish(system, PromotionDecided(r.id, r.number, status, items.map { it.objectId }))
    }
}

/** Objects in a promotion request under review are locked (roadmap 5.7). */
@Component
internal class PromotionLock(
    private val repository: PromotionRepository,
) : OperationGuard {
    private val locked =
        setOf(
            GuardedOperation.CHECK_OUT,
            GuardedOperation.SET_STATE,
            GuardedOperation.REVISE,
            GuardedOperation.MOVE,
            GuardedOperation.DELETE,
            GuardedOperation.RENAME,
        )

    override fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal> {
        if (check.operation !in locked || actor.principal.value == PROCESS_ACTOR) return emptyList()
        val id = check.objectId ?: return emptyList()
        val number = repository.lockOf(id) ?: return emptyList()
        return listOf(Refusal("change.promotion-lock", "site", "It's under review in promotion request $number"))
    }
}
