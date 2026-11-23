package com.plm.versioning.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.events.DomainEvents
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.IterationStatus
import com.plm.versioning.api.IterationsPurged
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectDeleted
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.PurgeService
import com.plm.versioning.api.VersioningService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
internal class DefaultPurgeService(
    private val versioning: VersioningService,
    private val jdbc: JdbcClient,
    private val guards: ObjectProvider<OperationGuard>,
    private val events: DomainEvents,
) : PurgeService {
    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators purge history")
    }

    private fun guard(
        actor: Actor,
        id: MasterId,
        purge: String,
    ) {
        val obj = versioning.get(actor, id)
        val check =
            OperationCheck(
                GuardedOperation.DELETE,
                obj.typeId,
                mapOf(
                    "operation" to GuardedOperation.DELETE.key,
                    "purge" to purge,
                ),
                id.value,
            ) { emptyMap() }
        val refusals = guards.orderedStream().toList().flatMap { it.check(actor, check) }
        if (refusals.isNotEmpty()) {
            throw ConflictException(
                "OPERATION_REFUSED",
                refusals.joinToString("; ") { it.message },
                mapOf(
                    "refusals" to refusals,
                ),
            )
        }
    }

    @Transactional(readOnly = true)
    override fun purgeable(
        actor: Actor,
        id: MasterId,
    ): List<Iteration> {
        requireAdmin(actor)
        val obj = versioning.get(actor, id)
        val history = versioning.history(actor, id).filter { it.status == IterationStatus.CHECKED_IN }
        // Of every version (revision, and one-off branch), its last iteration stays.
        val lastOfVersion =
            history
                .groupBy { it.revision to it.oneOff }
                .values
                .map { v ->
                    v.maxBy { it.iteration }.id
                }.toSet()
        val pinned =
            jdbc
                .sql(
                    """
                    select i.id from object_iteration i where i.master_id = :m and (
                        exists (select 1 from baseline_member b where b.iteration_id = i.id)
                        or exists (select 1 from part_document_link l where l.document_iteration_id = i.id))
                    """,
                ).param("m", id.value)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .list()
                .toSet()
        return history.filter { it.id !in lastOfVersion && it.id != obj.latest.id && it.id.value !in pinned }
    }

    override fun purgeIterations(
        actor: Actor,
        id: MasterId,
    ): Int {
        val doomed = purgeable(actor, id)
        if (doomed.isEmpty()) return 0
        guard(actor, id, "iterations")
        val obj = versioning.get(actor, id)
        jdbc
            .sql(
                "select set_config('plm.purging_master', :id, true)",
            ).param("id", id.value.toString())
            .query()
            .singleRow()
        val gone = doomed.map { it.id.value }.toSet()
        val all =
            jdbc
                .sql("select id, based_on_id from object_iteration where master_id = :m")
                .param("m", id.value)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
                .list()
                .toMap()
        // Re-chain the iterations that stay past the removed ones.
        for ((iteration, basedOn) in all) {
            if (iteration in gone || basedOn == null || basedOn !in gone) continue
            var target: UUID? = basedOn
            while (target != null && target in gone) target = all[target]
            jdbc
                .sql(
                    "update object_iteration set based_on_id = :b where id = :id",
                ).param("id", iteration)
                .param("b", target)
                .update()
        }
        jdbc.sql("delete from object_iteration where id in (:ids)").param("ids", gone.toList()).update()
        events.publish(actor, IterationsPurged(id.value, obj.number, doomed.map { it.version }))
        return doomed.size
    }

    override fun purgeObject(
        actor: Actor,
        id: MasterId,
    ) {
        requireAdmin(actor)
        val obj = versioning.get(actor, id)
        if (obj.checkedOutBy != null) throw ConflictException("CHECKED_OUT", "${obj.number} is checked out")
        guard(actor, id, "object")
        jdbc
            .sql(
                "select set_config('plm.deleting_master', :id, true)",
            ).param("id", id.value.toString())
            .query()
            .singleRow()
        jdbc.sql("delete from object_iteration where master_id = :id").param("id", id.value).update()
        jdbc.sql("delete from object_master where id = :id").param("id", id.value).update()
        events.publish(
            actor,
            ObjectDeleted(
                obj.id.value,
                obj.number,
                obj.typeId.value,
                obj.contextId.value,
                obj.latest.id.value,
                obj.latest.version,
                obj.name,
                obj.latest.state,
            ),
        )
    }
}
