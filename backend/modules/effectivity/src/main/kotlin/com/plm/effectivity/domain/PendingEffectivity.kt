package com.plm.effectivity.domain

import com.plm.effectivity.api.EffectivityKind
import com.plm.effectivity.api.EffectivityStatus
import com.plm.platform.actor.Actor
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.Refusal
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** What resolving a change notice does to effectivity: ranges to close, then ranges to put in effect. */
internal data class EffectivityPlan(
    val closes: List<Pair<Row, Range>>,
    val applies: List<Row>,
    val problems: List<String>,
)

/**
 * Pending effectivity (roadmap 7.3): ranges a change notice sets on its resulting versions take
 * effect when it's resolved. A revision's range the new one starts inside is closed the day, unit
 * or block before; a range it would swallow whole, or a lot range (no "lot before" to close at),
 * stops the notice from being resolved until someone sorts it out.
 */
@Component
internal class PendingEffectivity(
    private val jdbc: JdbcClient,
    private val service: DefaultEffectivityService,
) {
    fun isNotice(
        types: TypeService,
        actor: Actor,
        typeId: TypeId,
    ) = TypeId.CHANGE_NOTICE in types.lineage(actor, typeId)

    private fun rows(
        where: String,
        params: Map<String, Any?>,
    ): List<Row> =
        jdbc
            .sql("select * from effectivity where $where")
            .params(params)
            .query { rs, _ -> rowOf(rs) }
            .list()

    /** The value just before [v]: the day, unit or block before; none for lots. */
    @Suppress("UNCHECKED_CAST")
    private fun before(
        kind: EffectivityKind,
        v: Comparable<Any>,
    ): Comparable<Any>? =
        when (kind) {
            EffectivityKind.DATE -> (v as LocalDate).minusDays(1) as Comparable<Any>
            EffectivityKind.SERIAL, EffectivityKind.BLOCK ->
                (v as BigDecimal).subtract(
                    BigDecimal.ONE,
                ) as Comparable<Any>
            EffectivityKind.LOT -> null
        }

    fun plan(changeId: UUID): EffectivityPlan {
        val pending = rows("change_id = :c and status = 'PENDING'", mapOf("c" to changeId))
        val closes = ArrayList<Pair<Row, Range>>()
        val problems = ArrayList<String>()
        for (p in pending) {
            val others =
                rows(
                    """
                    master_id = :m and revision is not null and status = 'ACTUAL' and kind = :k
                    and context_id is not distinct from :ctx
                    """.trimIndent(),
                    mapOf("m" to p.masterId, "k" to p.range.kind.name, "ctx" to p.contextId),
                )
            for (o in others.filter { it.range.overlaps(p.range) }) {
                val r = o.range
                val cut = if (r.from < p.range.from as Any) before(r.kind, p.range.from) else null
                val what = "revision ${o.revision}'s ${r.kind.name.lowercase()} range ${r.text(
                    r.from,
                )} to ${r.text(r.to) ?: "open"}"
                when {
                    o.revision == p.revision ->
                        problems +=
                            "Revision ${p.revision} already has an overlapping range: $what"
                    cut == null && r.kind == EffectivityKind.LOT ->
                        problems +=
                            "A lot range can't be closed for you: end $what first"
                    cut == null -> problems += "Revision ${p.revision}'s new range would replace all of $what"
                    p.range.to != null && (r.to == null || r.to > p.range.to as Any) ->
                        problems += "Revision ${p.revision}'s new range would split $what in two"
                    else -> closes += o to r.copy(to = cut)
                }
            }
        }
        return EffectivityPlan(closes, pending, problems)
    }

    fun apply(plan: EffectivityPlan) {
        for ((row, range) in plan.closes) {
            jdbc
                .sql("update effectivity set to_num = :tn, to_date = :td, to_text = :tt where id = :id")
                .param("id", row.id)
                .params(service.columns(range).filterKeys { it.startsWith("t") })
                .update()
        }
        for (row in plan.applies) {
            jdbc.sql("update effectivity set status = 'ACTUAL' where id = :id").param("id", row.id).update()
        }
    }

    fun isPending(row: Row) = row.status == EffectivityStatus.PENDING
}

/** A notice whose effectivity can't take effect isn't resolved (roadmap 7.3). */
@Component
internal class PendingEffectivityGuard(
    private val pending: PendingEffectivity,
    private val types: TypeService,
) : OperationGuard {
    override fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal> {
        if (check.operation != GuardedOperation.SET_STATE || check.event["to"] != "RESOLVED") return emptyList()
        val id = check.objectId ?: return emptyList()
        if (!pending.isNotice(types, actor, check.typeId)) return emptyList()
        return pending.plan(id).problems.map { Refusal("effectivity.pending", "site", it) }
    }
}

/** On resolution, the notice's pending effectivity takes effect, closing what it supersedes. */
@Component
internal class PendingEffectivityOnResolve(
    private val pending: PendingEffectivity,
    private val types: TypeService,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "versioning.ObjectStateChanged.v1" || envelope.payload["to"] != "RESOLVED") return
        val system = Actor(TenantId(envelope.tenantId), PrincipalId("system"), siteAdmin = true)
        val typeId = TypeId(UUID.fromString(envelope.payload["typeId"].toString()))
        if (!pending.isNotice(types, system, typeId)) return
        val plan = pending.plan(UUID.fromString(envelope.payload["objectId"].toString()))
        // The guard refused resolution on problems; an override still applies what it can.
        pending.apply(plan)
    }
}
