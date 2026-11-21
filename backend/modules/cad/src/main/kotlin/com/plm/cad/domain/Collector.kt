package com.plm.cad.domain

import com.plm.cad.api.CadModel
import com.plm.cad.api.CollectOptions
import com.plm.cad.api.CollectReason
import com.plm.cad.api.CollectedModel
import com.plm.cad.api.CollectorOperation
import com.plm.cad.api.CollectorRequest
import com.plm.cad.api.CollectorResult
import com.plm.cad.api.CollectorService
import com.plm.cad.api.DependencyKind
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val MAX_COLLECT = 2_000

@Service
@Transactional
internal class DefaultCollectorService(
    private val jdbc: JdbcClient,
    private val cad: DefaultCadService,
    private val versioning: VersioningService,
) : CollectorService {
    private fun latest(masters: Collection<UUID>): Map<UUID, UUID> =
        if (masters.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql("select id, latest_iteration_id from object_master where id in (:ids)")
                .param("ids", masters)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
                .list()
                .toMap()
        }

    /** The CAD documents whose latest version depends on one of [ids] as [kinds]. */
    private fun users(
        ids: Collection<UUID>,
        kinds: Collection<DependencyKind>,
    ): Set<UUID> =
        if (ids.isEmpty()) {
            emptySet()
        } else {
            jdbc
                .sql(
                    """
                    select distinct m.id from cad_dependency d join object_master m on m.latest_iteration_id = d.parent_iteration_id
                    where d.child_master_id in (:ids) and d.kind in (:kinds)
                    """.trimIndent(),
                ).param("ids", ids)
                .param("kinds", kinds.map { it.name })
                .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .set()
        }

    @Transactional(readOnly = true)
    override fun collect(
        actor: Actor,
        id: UUID,
        options: CollectOptions,
    ): List<CollectedModel> {
        if (options.dependencies !in setOf("NONE", "REQUIRED", "ALL")) {
            throw ValidationException("INVALID_COLLECT", "Dependencies are NONE, REQUIRED or ALL")
        }
        cad.kindOf(actor, id) ?: throw ValidationException("NOT_A_CAD_DOCUMENT", "Only CAD documents are collected")
        val reasons = linkedMapOf(id to CollectReason.SELF)
        // Down: what it depends on, level by level (the latest version of each).
        if (options.dependencies != "NONE") {
            var level = setOf(id)
            while (level.isNotEmpty() && reasons.size < MAX_COLLECT) {
                val rows = cad.rows(latest(level).values)
                val next = mutableSetOf<UUID>()
                for (r in rows) {
                    if (r.kind == DependencyKind.DRAWING) continue
                    if (!r.required && options.dependencies == "REQUIRED") continue
                    val reason = if (r.required) CollectReason.REQUIRED else CollectReason.OPTIONAL
                    val before = reasons[r.childId]
                    if (before == null) {
                        reasons[r.childId] = reason
                        next += r.childId
                    } else if (before == CollectReason.OPTIONAL && reason == CollectReason.REQUIRED) {
                        reasons[r.childId] = reason
                    }
                }
                level = next
            }
        }
        if (options.parents) {
            users(listOf(id), listOf(DependencyKind.COMPONENT, DependencyKind.REFERENCE)).forEach {
                reasons.putIfAbsent(it, CollectReason.PARENT)
            }
        }
        if (options.drawings) {
            users(
                reasons.keys.toList(),
                listOf(DependencyKind.DRAWING),
            ).forEach { reasons.putIfAbsent(it, CollectReason.DRAWING) }
        }
        val objects =
            reasons.keys
                .chunked(500)
                .flatMap { versioning.list(actor, ObjectQuery(ids = it.map(::MasterId), limit = 500)) }
                .associateBy { it.id.value }
        return reasons.mapNotNull { (mid, reason) ->
            val o = objects[mid] ?: return@mapNotNull null
            CollectedModel(
                CadModel(
                    mid,
                    true,
                    o.number,
                    o.name,
                    o.typeId.value,
                    cad.kindOf(actor, mid),
                    o.latest.version,
                    o.latest.state,
                    false,
                ),
                reason,
                o.checkedOutBy,
            )
        }
    }

    override fun apply(
        actor: Actor,
        request: CollectorRequest,
    ): List<CollectorResult> {
        if (request.ids.isEmpty()) throw ValidationException("INVALID_COLLECT", "Choose at least one model")
        if (request.ids.size >
            MAX_COLLECT
        ) {
            throw ValidationException("INVALID_COLLECT", "At most $MAX_COLLECT models at once")
        }
        if (request.operation == CollectorOperation.SET_STATE && request.state.isNullOrBlank()) {
            throw ValidationException("INVALID_COLLECT", "Which state?")
        }
        val me = actor.principal.value
        // Children before their users for check-in, so each assembly is checked in after what it uses.
        val ordered = if (request.operation == CollectorOperation.CHECK_IN) request.ids.reversed() else request.ids
        return ordered.distinct().map { id ->
            val o = versioning.get(actor, MasterId(id))
            try {
                val (after, changed) =
                    when (request.operation) {
                        CollectorOperation.CHECK_OUT ->
                            if (o.checkedOutBy ==
                                me
                            ) {
                                o to false
                            } else {
                                versioning.checkOut(actor, o.id).let {
                                    versioning.get(actor, o.id) to
                                        true
                                }
                            }
                        CollectorOperation.CHECK_IN ->
                            if (o.checkedOutBy !=
                                me
                            ) {
                                o to false
                            } else {
                                versioning.checkIn(actor, o.id, request.comment) to true
                            }
                        CollectorOperation.UNDO_CHECK_OUT ->
                            if (o.checkedOutBy != me) o to false else versioning.undoCheckOut(actor, o.id) to true
                        CollectorOperation.REVISE -> versioning.revise(actor, o.id) to true
                        CollectorOperation.SET_STATE ->
                            if (o.latest.state == request.state) {
                                o to false
                            } else {
                                versioning.setState(actor, o.id, SetState(request.state!!, request.comment)) to true
                            }
                    }
                CollectorResult(after.id.value, after.number, after.latest.version, after.latest.state, changed)
            } catch (e: PlmException) {
                // All or nothing: the transaction rolls back; say which model stopped it.
                throw collectorFailed(o.number, e)
            }
        }
    }
}

/** One model couldn't take the operation; nothing was done. */
private fun collectorFailed(
    number: String,
    cause: PlmException,
) = com.plm.platform.error
    .ConflictException(cause.code, "$number: ${cause.message}", mapOf("number" to number))
