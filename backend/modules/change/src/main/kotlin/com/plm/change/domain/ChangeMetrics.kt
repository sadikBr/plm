package com.plm.change.domain

import com.plm.change.api.ChangeAges
import com.plm.change.api.ChangeKind
import com.plm.change.api.ChangeMetrics
import com.plm.change.api.ChangeMetricsService
import com.plm.change.api.ChangeStateCount
import com.plm.change.api.CycleTime
import com.plm.change.api.MyChange
import com.plm.change.api.WeekFlow
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

private val CLOSED_STATES = setOf("RESOLVED", "CANCELLED", "REJECTED")
private const val MAX_RECORDS = 20_000

private data class Record(
    val id: UUID,
    val kind: ChangeKind,
    val state: String,
    val createdAt: Instant,
    val resolvedAt: Instant?,
)

/** Keeps `change_record` in step with the change objects' events (roadmap 5.12). */
@Component
internal class ChangeRecords(
    private val jdbc: JdbcClient,
    private val types: TypeService,
    private val versioning: VersioningService,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        val p = envelope.payload
        when (envelope.eventType) {
            "versioning.ObjectCreated.v1" -> {
                val system = Actor(TenantId(envelope.tenantId), PrincipalId(PROCESS_ACTOR), siteAdmin = true)
                val kind = changeKind(types, system, TypeId(UUID.fromString(p["typeId"].toString()))) ?: return
                val state =
                    versioning
                        .list(system, ObjectQuery(ids = listOf(MasterId(envelope.aggregateId)), limit = 1))
                        .firstOrNull()
                        ?.latest
                        ?.state ?: return
                jdbc
                    .sql(
                        """
                        insert into change_record (id, tenant_id, context_id, kind, state, created_at, state_since)
                        values (:id, :t, :ctx, :kind, :state, :at, :at)
                        on conflict (id) do nothing
                        """.trimIndent(),
                    ).param("id", envelope.aggregateId)
                    .param("state", state)
                    .param("t", envelope.tenantId)
                    .param("ctx", UUID.fromString(p["contextId"].toString()))
                    .param("kind", kind.name)
                    .param("at", Timestamp.from(envelope.occurredAt))
                    .update()
            }
            "versioning.ObjectStateChanged.v1" -> {
                val to = p["to"].toString()
                jdbc
                    .sql(
                        """
                        update change_record set state = :to, state_since = :at,
                            resolved_at = case when :to = 'RESOLVED' then :at else resolved_at end
                        where id = :id
                        """.trimIndent(),
                    ).param("to", to)
                    .param("at", Timestamp.from(envelope.occurredAt))
                    .param("id", envelope.aggregateId)
                    .update()
            }
            "versioning.ObjectMoved.v1" ->
                jdbc
                    .sql("update change_record set context_id = :ctx where id = :id")
                    .param("ctx", UUID.fromString(p["contextId"].toString()))
                    .param("id", envelope.aggregateId)
                    .update()
            "versioning.ObjectDeleted.v1" ->
                jdbc.sql("delete from change_record where id = :id").param("id", envelope.aggregateId).update()
        }
    }
}

@Service
@Transactional(readOnly = true)
internal class DefaultChangeMetricsService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val clock: Clock,
) : ChangeMetricsService {
    override fun metrics(
        actor: Actor,
        contextId: UUID?,
        periodDays: Int,
        weeks: Int,
    ): ChangeMetrics {
        if (periodDays !in 1..3650 || weeks !in 1..104) {
            throw ValidationException("INVALID_PERIOD", "The period is 1 to 3650 days, over 1 to 104 weeks")
        }
        val now = clock.instant()
        val since = minOf(now.minus(Duration.ofDays(periodDays.toLong())), now.minus(Duration.ofDays(weeks * 7L)))
        val rows =
            jdbc
                .sql(
                    """
                    select id, kind, state, created_at, resolved_at from change_record
                    where tenant_id = :t and (cast(:ctx as uuid) is null or context_id = :ctx)
                      and (state not in ('RESOLVED', 'CANCELLED', 'REJECTED') or state_since >= :since or created_at >= :since)
                    order by created_at desc
                    limit $MAX_RECORDS
                    """.trimIndent(),
                ).param("t", actor.tenant.value)
                .param("ctx", contextId)
                .param("since", Timestamp.from(since))
                .query { rs, _ ->
                    Record(
                        rs.getObject("id", UUID::class.java),
                        ChangeKind.valueOf(rs.getString("kind")),
                        rs.getString("state"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("resolved_at")?.toInstant(),
                    )
                }.list()
        // Only what the reader may read counts.
        val readable =
            rows
                .chunked(500)
                .flatMap { chunk ->
                    versioning.list(actor, ObjectQuery(ids = chunk.map { MasterId(it.id) }, limit = 500))
                }.associateBy { it.id.value }
        val records = rows.filter { it.id in readable }
        val open = records.filter { it.state !in CLOSED_STATES }
        val days = { from: Instant, to: Instant -> Duration.between(from, to).toHours() / 24.0 }

        val byState =
            open
                .groupBy { it.kind to it.state }
                .map { (k, v) -> ChangeStateCount(k.first, k.second, v.size) }
                .sortedWith(compareBy({ it.kind.ordinal }, { it.state }))
        val ages =
            open.groupBy { it.kind }.toSortedMap().map { (kind, list) ->
                val age = list.map { days(it.createdAt, now) }
                ChangeAges(
                    kind,
                    age.count { it <= 7 },
                    age.count { it > 7 && it <= 30 },
                    age.count { it > 30 && it <= 90 },
                    age.count { it > 90 },
                )
            }
        val periodStart = now.minus(Duration.ofDays(periodDays.toLong()))
        val cycleTimes =
            records
                .filter { it.resolvedAt != null && it.state == "RESOLVED" && it.resolvedAt >= periodStart }
                .groupBy { it.kind }
                .toSortedMap()
                .map { (kind, list) ->
                    val d = list.map { days(it.createdAt, it.resolvedAt!!) }.sorted()
                    val median = if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
                    CycleTime(kind, d.size, d.average(), median)
                }
        val monday =
            now
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val flow =
            (weeks - 1 downTo 0).map { back ->
                val start = monday.minusWeeks(back.toLong())
                val from = start.atStartOfDay().toInstant(ZoneOffset.UTC)
                val to = start.plusWeeks(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                WeekFlow(
                    start,
                    records.count { it.createdAt >= from && it.createdAt < to },
                    records.count { it.resolvedAt != null && it.resolvedAt >= from && it.resolvedAt < to },
                )
            }

        val me = actor.principal.value
        val planned =
            if (open.isEmpty()) {
                emptyMap()
            } else {
                jdbc
                    .sql("select task_id, due_date from change_plan where assignee = :me and task_id in (:ids)")
                    .param("me", me)
                    .param("ids", open.map { it.id })
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getDate(2)?.toLocalDate() }
                    .list()
                    .toMap()
            }
        val mine =
            open
                .mapNotNull { r ->
                    val o = readable.getValue(r.id)
                    val why =
                        when {
                            r.id in planned -> "ASSIGNEE"
                            o.owner == me -> "OWNER"
                            else -> return@mapNotNull null
                        }
                    MyChange(
                        r.id,
                        o.number,
                        o.name,
                        r.kind,
                        r.state,
                        days(r.createdAt, now).toInt(),
                        why,
                        planned[r.id],
                    )
                }.sortedWith(compareBy(nullsLast()) { it.due })
                .take(100)
        return ChangeMetrics(open.size, byState, ages, cycleTimes, flow, mine, periodDays)
    }
}
