package com.plm.change.domain

import com.plm.change.api.ChangeKind
import com.plm.change.api.ChangeRole
import com.plm.platform.actor.Actor
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.Refusal
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

private val log = LoggerFactory.getLogger(NoticeCompletion::class.java)

private val CLOSED = setOf("RESOLVED", "CANCELLED", "REJECTED")

/** A change notice is resolved once its tasks are: until then, it can't be (roadmap 5.9). */
@Component
internal class OpenTasksBlockResolution(
    private val changes: DefaultChangeService,
    private val types: TypeService,
    private val versioning: VersioningService,
) : OperationGuard {
    override fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal> {
        if (check.operation != GuardedOperation.SET_STATE || check.event["to"] != "RESOLVED") return emptyList()
        val id = check.objectId ?: return emptyList()
        if (changeKind(types, actor, check.typeId) != ChangeKind.CHANGE_NOTICE) return emptyList()
        val tasks = changes.rows(changeId = id).filter { it.role == ChangeRole.TASK }.map { MasterId(it.targetId) }
        if (tasks.isEmpty()) return emptyList()
        val system = Actor(actor.tenant, PrincipalId(PROCESS_ACTOR), siteAdmin = true)
        val open = versioning.list(system, ObjectQuery(ids = tasks, limit = 500)).filter { it.latest.state !in CLOSED }
        if (open.isEmpty()) return emptyList()
        return listOf(
            Refusal(
                "change.open-tasks",
                "site",
                "Its tasks aren't done: ${open.joinToString(", ") { "${it.number} (${it.latest.state})" }}",
            ),
        )
    }
}

/**
 * When a change notice is resolved (roadmap 5.9): its resulting objects are released, and the
 * requests it implements, and the reports they address, are resolved with it.
 */
@Component
internal class NoticeCompletion(
    private val changes: DefaultChangeService,
    private val types: TypeService,
    private val versioning: VersioningService,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "versioning.ObjectStateChanged.v1" || envelope.payload["to"] != "RESOLVED") return
        val tenant = TenantId(envelope.tenantId)
        val system = Actor(tenant, PrincipalId(PROCESS_ACTOR), siteAdmin = true)
        val typeId = TypeId(UUID.fromString(envelope.payload["typeId"].toString()))
        if (changeKind(types, system, typeId) != ChangeKind.CHANGE_NOTICE) return
        val notice = UUID.fromString(envelope.payload["objectId"].toString())
        val links = changes.rows(changeId = notice)
        val number = envelope.payload["number"]
        for (r in links.filter { it.role == ChangeRole.RESULTING }) {
            move(
                system,
                r.targetId,
                "RELEASED",
                "Released by $number",
            )
        }
        for (cr in links.filter { it.role == ChangeRole.IMPLEMENTS }) {
            move(system, cr.targetId, "RESOLVED", "Implemented by $number")
            changes.rows(changeId = cr.targetId).filter { it.role == ChangeRole.ADDRESSES }.forEach {
                move(system, it.targetId, "RESOLVED", "Fixed by $number")
            }
        }
    }

    /** To [state], along the lifecycle where it can, overriding where it can't: the notice decided it. */
    private fun move(
        system: Actor,
        id: UUID,
        state: String,
        comment: String,
    ) {
        val o = versioning.list(system, ObjectQuery(ids = listOf(MasterId(id)), limit = 1)).firstOrNull() ?: return
        if (o.latest.state == state || o.checkedOutBy != null) return
        val lifecycle = versioning.lifecycle(system, o.id)
        if (lifecycle.template.states.none { it.key == state }) return
        versioning.setState(system, o.id, SetState(state, comment, override = state !in lifecycle.nextStates))
        log.info("{} is now {}: {}", o.number, state, comment)
    }
}
