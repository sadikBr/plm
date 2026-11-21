package com.plm.bom.domain

import com.plm.bom.infra.BomRepository
import com.plm.platform.actor.Actor
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.Refusal
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Structure follows the iteration it's based on (roadmap 4.1): a working copy starts with the
 * latest's lines, a new revision with its predecessor's. A discarded working copy takes its lines
 * with it (the database cascades).
 */
@Component
internal class StructureFollowsIterations(
    private val repository: BomRepository,
    private val clock: Clock,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType == "versioning.ObjectCheckedOut.v1" ||
            envelope.eventType == "versioning.ObjectRevised.v1"
        ) {
            val iterationId = UUID.fromString(envelope.payload["iterationId"].toString())
            repository.carryOver(iterationId) { UuidV7.generate(clock) }
        }
    }
}

/** A part used in some structure can't be deleted: it would leave holes in products. */
@Component
internal class UsedPartsStay(
    private val repository: BomRepository,
) : OperationGuard {
    override fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal> {
        if (check.operation != GuardedOperation.DELETE) return emptyList()
        val id = check.objectId ?: return emptyList()
        val parents = repository.usedIn(id)
        if (parents == 0) return emptyList()
        return listOf(
            Refusal(
                "bom.used-parts-stay",
                "site",
                "It's used in the structure of ${if (parents == 1) "1 part" else "$parents parts"}: remove it from there first",
            ),
        )
    }
}
