package com.plm.cad.domain

import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.UpdateUsage
import com.plm.cad.api.Associate
import com.plm.cad.api.AssociationKind
import com.plm.cad.api.AssociationService
import com.plm.cad.api.BuildRequest
import com.plm.cad.api.BuildResult
import com.plm.cad.api.BuiltLine
import com.plm.cad.api.CadAssociation
import com.plm.cad.api.CadKind
import com.plm.cad.api.DependencyKind
import com.plm.cad.api.PartRef
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

private data class AssociationRow(
    val id: UUID,
    val partIteration: UUID,
    val partId: UUID,
    val cadId: UUID,
    val kind: AssociationKind,
)

@Service
@Transactional
internal class DefaultAssociationService(
    private val jdbc: JdbcClient,
    private val cad: DefaultCadService,
    private val bom: BomService,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val clock: Clock,
) : AssociationService {
    private fun bad(message: String): Nothing = throw ValidationException("INVALID_ASSOCIATION", message)

    private fun isPart(
        actor: Actor,
        o: VersionedObject,
    ) = TypeId.PART in types.lineage(actor, o.typeId)

    private val mapper =
        org.springframework.jdbc.core.RowMapper { rs, _ ->
            AssociationRow(
                rs.getObject("id", UUID::class.java),
                rs.getObject("part_iteration_id", UUID::class.java),
                rs.getObject("master_id", UUID::class.java),
                rs.getObject("cad_master_id", UUID::class.java),
                AssociationKind.valueOf(rs.getString("kind")),
            )
        }

    private fun byPartIteration(iteration: UUID): List<AssociationRow> =
        jdbc
            .sql(
                """
                select a.*, i.master_id from cad_association a join object_iteration i on i.id = a.part_iteration_id
                where a.part_iteration_id = :i order by a.kind desc, a.id
                """.trimIndent(),
            ).param("i", iteration)
            .query(mapper)
            .list()

    /** The associations of parts' latest versions with any of [models]. */
    private fun byModels(
        models: Collection<UUID>,
        kind: AssociationKind? = null,
    ): List<AssociationRow> =
        if (models.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql(
                    """
                    select a.*, m.id as master_id from cad_association a join object_master m on m.latest_iteration_id = a.part_iteration_id
                    where a.cad_master_id in (:ids) and (cast(:kind as text) is null or a.kind = :kind)
                    """.trimIndent(),
                ).param("ids", models)
                .param("kind", kind?.name)
                .query(mapper)
                .list()
        }

    private fun parts(
        actor: Actor,
        ids: Collection<UUID>,
    ): Map<UUID, PartRef> {
        val found =
            ids
                .distinct()
                .chunked(500)
                .flatMap { versioning.list(actor, ObjectQuery(ids = it.map(::MasterId), limit = 500)) }
                .associateBy { it.id.value }
        return ids.distinct().associateWith { id ->
            found[id].let { PartRef(id, it != null, it?.number, it?.name, it?.latest?.version, it?.latest?.state) }
        }
    }

    private fun views(
        actor: Actor,
        rows: List<AssociationRow>,
    ): List<CadAssociation> {
        val parts = parts(actor, rows.map { it.partId })
        val models = cad.models(actor, rows.map { it.cadId })
        return rows
            .filter { parts.getValue(it.partId).readable }
            .map { CadAssociation(it.id, it.kind, parts.getValue(it.partId), models.getValue(it.cadId)) }
    }

    @Transactional(readOnly = true)
    override fun associations(
        actor: Actor,
        id: UUID,
        workingCopy: Boolean,
    ): List<CadAssociation> {
        val o = versioning.get(actor, MasterId(id))
        val rows =
            if (isPart(actor, o)) {
                byPartIteration(if (workingCopy) versioning.workingCopy(actor, o.id).id.value else o.latest.id.value)
            } else {
                byModels(listOf(id))
            }
        return views(actor, rows)
    }

    private fun partWorkingCopy(
        actor: Actor,
        partId: UUID,
    ): Pair<VersionedObject, UUID> {
        val part = versioning.get(actor, MasterId(partId))
        if (!isPart(actor, part)) throw ValidationException("NOT_A_PART", "${part.number} isn't a part")
        return part to versioning.workingCopy(actor, part.id).id.value
    }

    override fun associate(
        actor: Actor,
        partId: UUID,
        request: Associate,
    ): CadAssociation {
        val (part, wc) = partWorkingCopy(actor, partId)
        val model = versioning.get(actor, MasterId(request.cadId))
        val kind =
            cadKind(types, actor, model.typeId)
                ?: throw ValidationException("NOT_A_CAD_DOCUMENT", "${model.number} isn't a CAD document")
        if (request.kind == AssociationKind.OWNER) {
            if (kind == CadKind.DRAWING) bad("A drawing can't own a part: associate its model")
            if (byPartIteration(
                    wc,
                ).any { it.kind == AssociationKind.OWNER }
            ) {
                bad("${part.number} already has an owning model")
            }
            byModels(listOf(model.id.value), AssociationKind.OWNER).firstOrNull { it.partId != partId }?.let { other ->
                val owner =
                    versioning
                        .list(
                            actor,
                            ObjectQuery(ids = listOf(MasterId(other.partId)), limit = 1),
                        ).firstOrNull()
                throw ConflictException(
                    "INVALID_ASSOCIATION",
                    "${model.number} already owns ${owner?.number ?: "another part"}",
                )
            }
        }
        val id = UuidV7.generate(clock)
        val inserted =
            jdbc
                .sql(
                    """
                    insert into cad_association (id, tenant_id, part_iteration_id, cad_master_id, kind)
                    values (:id, :t, :i, :c, :k) on conflict do nothing
                    """.trimIndent(),
                ).param("id", id)
                .param("t", actor.tenant.value)
                .param("i", wc)
                .param("c", model.id.value)
                .param("k", request.kind.name)
                .update()
        if (inserted == 0) bad("${model.number} is already associated with ${part.number}")
        return views(actor, byPartIteration(wc)).first { it.id == id }
    }

    override fun dissociate(
        actor: Actor,
        partId: UUID,
        associationId: UUID,
    ) {
        val (_, wc) = partWorkingCopy(actor, partId)
        val n =
            jdbc
                .sql("delete from cad_association where id = :id and part_iteration_id = :i")
                .param("id", associationId)
                .param("i", wc)
                .update()
        if (n == 0) throw NotFoundException("ASSOCIATION_NOT_FOUND", "No such association on the working copy")
    }

    override fun build(
        actor: Actor,
        partId: UUID,
        request: BuildRequest,
    ): BuildResult {
        val (part, wc) = partWorkingCopy(actor, partId)
        val owner =
            byPartIteration(wc).firstOrNull { it.kind == AssociationKind.OWNER }
                ?: throw ConflictException("NO_OWNING_MODEL", "${part.number} has no owning CAD model to build from")
        val modelLatest =
            versioning
                .get(actor, MasterId(owner.cadId))
                .latest.id.value
        val components = cad.rows(listOf(modelLatest)).filter { it.kind == DependencyKind.COMPONENT }
        val occurrences = components.groupingBy { it.childId }.eachCount()
        val owners =
            byModels(
                occurrences.keys,
                AssociationKind.OWNER,
            ).associate { it.cadId to it.partId }.toMutableMap()
        val models = cad.models(actor, occurrences.keys + owner.cadId)

        val created = mutableListOf<UUID>()
        if (request.createParts) {
            for (m in occurrences.keys.filter { it !in owners }) {
                val model = models.getValue(m)
                if (!model.readable) continue
                val newPart =
                    versioning.create(
                        actor,
                        CreateVersionedObject(
                            TypeId.PART,
                            part.contextId,
                            model.name ?: model.number ?: "Part",
                        ),
                    )
                jdbc
                    .sql(
                        "insert into cad_association (id, tenant_id, part_iteration_id, cad_master_id, kind) values (:id, :t, :i, :c, 'OWNER')",
                    ).param("id", UuidV7.generate(clock))
                    .param("t", actor.tenant.value)
                    .param("i", newPart.latest.id.value)
                    .param("c", m)
                    .update()
                owners[m] = newPart.id.value
                created += newPart.id.value
            }
        }
        // What the structure should hold: each component part, as many as it occurs.
        val target = mutableMapOf<UUID, Int>()
        occurrences.forEach { (m, n) -> owners[m]?.let { target.merge(it, n, Int::plus) } }

        val lines = bom.usages(actor, part.id, workingCopy = true)
        val fromCad =
            if (lines.isEmpty()) {
                emptySet()
            } else {
                jdbc
                    .sql(
                        """
                        select distinct m.id from object_master m join cad_association a on a.part_iteration_id = m.latest_iteration_id
                        where a.kind = 'OWNER' and m.id in (:ids)
                        """.trimIndent(),
                    ).param("ids", lines.map { it.child.id })
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                    .set()
            }
        val result = mutableListOf<Pair<UUID, Pair<BigDecimal, String>>>()
        for ((child, group) in lines.groupBy { it.child.id }) {
            val wanted = target.remove(child)
            when {
                wanted != null -> {
                    val first = group.first()
                    group.drop(1).forEach { bom.remove(actor, part.id, it.id) }
                    val qty = BigDecimal(wanted)
                    if (first.quantity.compareTo(qty) != 0 || group.size > 1) {
                        val designators = first.designators.takeIf { it.size == wanted }?.joinToString(", ")
                        bom.update(
                            actor,
                            part.id,
                            first.id,
                            UpdateUsage(qty, first.unit, first.lineNumber, first.findNumber, first.notes, designators),
                        )
                        result += child to (qty to "UPDATED")
                    } else {
                        result += child to (qty to "UNCHANGED")
                    }
                }
                child in fromCad -> {
                    group.forEach { bom.remove(actor, part.id, it.id) }
                    result += child to (group.sumOf { it.quantity } to "REMOVED")
                }
                else -> result += child to (group.sumOf { it.quantity } to "KEPT")
            }
        }
        for ((child, n) in target) {
            bom.add(actor, part.id, AddUsage(child, BigDecimal(n)))
            result += child to (BigDecimal(n) to "ADDED")
        }
        val refs = parts(actor, result.map { it.first } + created)
        return BuildResult(
            models.getValue(owner.cadId),
            result.map { (id, r) -> BuiltLine(refs.getValue(id), r.first, r.second) },
            occurrences.keys.filter { it !in owners }.map { models.getValue(it) },
            created.map { refs.getValue(it) },
        )
    }
}
