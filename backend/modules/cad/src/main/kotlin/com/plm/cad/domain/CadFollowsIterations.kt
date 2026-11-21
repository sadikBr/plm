package com.plm.cad.domain

import com.plm.platform.actor.Actor
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.Refusal
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Dependencies follow the iteration they're based on (roadmap 6.1): a working copy starts with the
 * latest's, a new revision with its predecessor's. A discarded working copy takes its own with it.
 */
@Component
internal class CadFollowsIterations(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "versioning.ObjectCheckedOut.v1" &&
            envelope.eventType != "versioning.ObjectRevised.v1"
        ) {
            return
        }
        val iteration = UUID.fromString(envelope.payload["iterationId"].toString())
        val source =
            jdbc
                .sql("select based_on_id from object_iteration where id = :id")
                .param("id", iteration)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .optional()
                .orElse(null) ?: return
        val existing =
            jdbc
                .sql("select count(*) from cad_dependency where parent_iteration_id = :id")
                .param("id", iteration)
                .query(Int::class.java)
                .single()
        if (existing > 0) return
        val ids =
            jdbc
                .sql("select id from cad_dependency where parent_iteration_id = :s order by position")
                .param("s", source)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .list()
        for (old in ids) {
            jdbc
                .sql(
                    """
                    insert into cad_dependency (id, tenant_id, parent_iteration_id, child_master_id, kind, required, occurrence,
                        transform, position, created_at, created_by)
                    select :new, tenant_id, :it, child_master_id, kind, required, occurrence, transform, position, created_at, created_by
                    from cad_dependency where id = :old
                    """.trimIndent(),
                ).param("new", UuidV7.generate(clock))
                .param("it", iteration)
                .param("old", old)
                .update()
        }
    }
}

/** A CAD document others depend on can't be deleted: they'd no longer open. */
@Component
internal class UsedModelsStay(
    private val jdbc: JdbcClient,
) : OperationGuard {
    override fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal> {
        if (check.operation != GuardedOperation.DELETE) return emptyList()
        val id = check.objectId ?: return emptyList()
        val users =
            jdbc
                .sql(
                    """
                    select count(distinct i.master_id) from cad_dependency d join object_iteration i on i.id = d.parent_iteration_id
                    where d.child_master_id = :id
                    """.trimIndent(),
                ).param("id", id)
                .query(Int::class.java)
                .single()
        if (users == 0) return emptyList()
        return listOf(
            Refusal(
                "cad.used-models-stay",
                "site",
                "${if (users == 1) "A CAD document depends" else "$users CAD documents depend"} on it: remove it from there first",
            ),
        )
    }
}
