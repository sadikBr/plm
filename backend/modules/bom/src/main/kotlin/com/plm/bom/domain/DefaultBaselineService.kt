package com.plm.bom.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.Permission
import com.plm.bom.api.Baseline
import com.plm.bom.api.BaselineChanged
import com.plm.bom.api.BaselineService
import com.plm.bom.api.BaselineStatus
import com.plm.bom.api.BomService
import com.plm.bom.api.CollectRequest
import com.plm.bom.api.CollectedObject
import com.plm.bom.api.UsedPart
import com.plm.bom.infra.DocumentLinkRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultBaselineService(
    private val jdbc: JdbcClient,
    private val bom: BomService,
    private val documentLinks: DocumentLinkRepository,
    private val versioning: VersioningService,
    private val contexts: ContextService,
    private val access: AccessService,
    private val events: DomainEvents,
    private val clock: Clock,
) : BaselineService {
    private fun readable(actor: Actor) =
        access.filter(actor, Permission.READ, AccessColumns("m.context_id", "m.type_id", "li.state", "m.id"))

    private val mapper =
        RowMapper { rs, _ ->
            Baseline(
                rs.getObject("id", UUID::class.java),
                rs.getString("number"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("context_id", UUID::class.java),
                BaselineStatus.valueOf(rs.getString("status")),
                rs.getInt("members"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"),
                rs.getTimestamp("frozen_at")?.toInstant(),
                rs.getString("frozen_by"),
            )
        }

    private val select =
        "select b.*, (select count(*) from baseline_member bm where bm.baseline_id = b.id) as members from baseline b"

    private fun visible(
        actor: Actor,
        b: Baseline,
    ) = actor.siteAdmin || Permission.READ in access.permissions(actor, AccessTarget(ContextId(b.contextId)))

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        containing: MasterId?,
    ): List<Baseline> =
        jdbc
            .sql(
                "$select where b.tenant_id = :tenant and (cast(:part as uuid) is null or exists " +
                    "(select 1 from baseline_member bm where bm.baseline_id = b.id and bm.master_id = :part)) order by b.created_at desc limit 500",
            ).param("tenant", actor.tenant.value)
            .param("part", containing?.value)
            .query(mapper)
            .list()
            .filter { visible(actor, it) }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: UUID,
    ): Baseline {
        val b =
            jdbc
                .sql("$select where b.tenant_id = :tenant and b.id = :id")
                .param("tenant", actor.tenant.value)
                .param("id", id)
                .query(mapper)
                .optional()
                .orElse(null)
        if (b == null || !visible(actor, b)) throw NotFoundException("BASELINE_NOT_FOUND", "There's no such baseline")
        return b
    }

    @Transactional(readOnly = true)
    override fun members(
        actor: Actor,
        id: UUID,
    ): List<CollectedObject> {
        get(actor, id)
        val filter = readable(actor)
        return jdbc
            .sql(
                """
                select m.id, m.number, m.name, m.type_id, i.id as it_id, i.revision, i.iteration, i.state
                from baseline_member bm
                join object_master m on m.id = bm.master_id
                join object_iteration li on li.id = m.latest_iteration_id
                join object_iteration i on i.id = bm.iteration_id
                where bm.baseline_id = :id and ${filter.sql}
                order by m.number
                """.trimIndent(),
            ).params(filter.params)
            .param("id", id)
            .query { rs, _ ->
                CollectedObject(
                    UsedPart(
                        rs.getObject("id", UUID::class.java),
                        true,
                        rs.getString("number"),
                        rs.getString("name"),
                        rs.getObject("type_id", UUID::class.java),
                        rs.getObject("it_id", UUID::class.java),
                        rs.getString("revision") + "." + rs.getInt("iteration"),
                        rs.getString("state"),
                        false,
                    ),
                    "MEMBER",
                    0,
                )
            }.list()
    }

    /** Its creator, the context's managers and site administrators change it, while it's open. */
    private fun changeable(
        actor: Actor,
        id: UUID,
    ): Baseline {
        val b = get(actor, id)
        val manager = Permission.MANAGE in access.permissions(actor, AccessTarget(ContextId(b.contextId)))
        if (!actor.siteAdmin && !manager && b.createdBy != actor.principal.value) {
            throw ForbiddenException("ACCESS_DENIED", "Only its creator or the context's managers can change it")
        }
        if (b.status ==
            BaselineStatus.FROZEN
        ) {
            throw ConflictException("BASELINE_FROZEN", "${b.number} is frozen: it can't change")
        }
        return b
    }

    override fun create(
        actor: Actor,
        baseline: com.plm.bom.api.NewBaseline,
    ): Baseline {
        val name = baseline.name.trim()
        if (name.isEmpty() ||
            name.length > 200
        ) {
            throw ValidationException("INVALID_BASELINE", "A baseline needs a name (at most 200 characters)")
        }
        contexts.get(actor, ContextId(baseline.contextId))
        access.require(actor, AccessTarget(ContextId(baseline.contextId)), Permission.CREATE)
        val id = UuidV7.generate(clock)
        val seq = jdbc.sql("select nextval('baseline_number_seq')").query(Long::class.java).single()
        val number = "BL-%05d".format(seq)
        jdbc
            .sql(
                """
                insert into baseline (id, tenant_id, number, name, description, context_id, created_at, created_by)
                values (:id, :tenant, :number, :name, :description, :context, :at, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", actor.tenant.value)
            .param("number", number)
            .param("name", name)
            .param(
                "description",
                baseline.description
                    ?.trim()
                    ?.ifEmpty { null }
                    ?.take(2000),
            ).param("context", baseline.contextId)
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        events.publish(actor, BaselineChanged(id, number, "CREATED", 0))
        return get(actor, id)
    }

    override fun add(
        actor: Actor,
        id: UUID,
        iterationIds: List<UUID>,
    ): Baseline {
        val b = changeable(actor, id)
        if (iterationIds.size > 50_000) throw ValidationException("INVALID_BASELINE", "At most 50,000 objects at once")
        // Only checked-in iterations of objects the actor may read.
        val filter = readable(actor)
        val rows =
            jdbc
                .sql(
                    """
                    select i.id, i.master_id from object_iteration i
                    join object_master m on m.id = i.master_id
                    join object_iteration li on li.id = m.latest_iteration_id
                    where i.id = any(cast(:ids as uuid[])) and i.status = 'CHECKED_IN' and ${filter.sql}
                    """.trimIndent(),
                ).params(filter.params)
                .param("ids", iterationIds.distinct().toTypedArray())
                .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("master_id", UUID::class.java) }
                .list()
        if (rows.size != iterationIds.distinct().size) {
            throw ValidationException(
                "INVALID_BASELINE",
                "Only checked-in versions of objects you can read go into a baseline",
            )
        }
        for ((iteration, master) in rows) {
            jdbc
                .sql(
                    """
                    insert into baseline_member (baseline_id, master_id, iteration_id) values (:b, :m, :i)
                    on conflict (baseline_id, master_id) do update set iteration_id = excluded.iteration_id
                    """.trimIndent(),
                ).param("b", id)
                .param("m", master)
                .param("i", iteration)
                .update()
        }
        events.publish(actor, BaselineChanged(id, b.number, "MEMBERS_ADDED", rows.size))
        return get(actor, id)
    }

    override fun remove(
        actor: Actor,
        id: UUID,
        masterIds: List<UUID>,
    ): Baseline {
        val b = changeable(actor, id)
        val n =
            jdbc
                .sql("delete from baseline_member where baseline_id = :b and master_id = any(cast(:ids as uuid[]))")
                .param("b", id)
                .param("ids", masterIds.toTypedArray())
                .update()
        events.publish(actor, BaselineChanged(id, b.number, "MEMBERS_REMOVED", n))
        return get(actor, id)
    }

    override fun freeze(
        actor: Actor,
        id: UUID,
    ): Baseline {
        val b = changeable(actor, id)
        jdbc
            .sql("update baseline set status = 'FROZEN', frozen_at = :at, frozen_by = :by where id = :id")
            .param("id", id)
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        events.publish(actor, BaselineChanged(id, b.number, "FROZEN", b.members))
        return get(actor, id)
    }

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        val b = changeable(actor, id)
        jdbc.sql("delete from baseline where id = :id").param("id", id).update()
        events.publish(actor, BaselineChanged(id, b.number, "DELETED", b.members))
    }

    @Transactional(readOnly = true)
    override fun collect(
        actor: Actor,
        request: CollectRequest,
    ): List<CollectedObject> {
        val structure =
            bom.structure(
                actor,
                MasterId(request.partId),
                request.spec,
                request.depth.coerceIn(1, 50),
                limit = 50_000,
            )
        val root = versioning.get(actor, MasterId(request.partId))
        // The state of the iteration collected, not of the latest.
        val rootState =
            structure.rootIterationId?.let { id ->
                versioning.history(actor, root.id).firstOrNull { it.id.value == id }?.state
            } ?: root.latest.state
        val out = LinkedHashMap<UUID, CollectedObject>()
        out[root.id.value] =
            CollectedObject(
                UsedPart(
                    root.id.value,
                    true,
                    root.number,
                    root.name,
                    root.typeId.value,
                    structure.rootIterationId,
                    structure.rootVersion,
                    rootState,
                    true,
                ),
                "TOP",
                0,
            )
        for (n in structure.nodes) {
            val c = n.line.child
            if (c.readable &&
                c.iterationId != null &&
                c.id !in out
            ) {
                out[c.id] = CollectedObject(c, "STRUCTURE", n.level)
            }
        }
        if (request.documents) {
            val filter = readable(actor)
            for (part in out.values.toList()) {
                val iteration = part.item.iterationId ?: continue
                for (link in documentLinks.links(iteration, filter)) {
                    val d = link.document
                    if (d.readable && d.id !in out) out[d.id] = CollectedObject(d, link.kind.name, part.level)
                }
            }
        }
        return out.values.toList()
    }
}
