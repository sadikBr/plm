package com.plm.cad.domain

import com.plm.cad.api.AddDependency
import com.plm.cad.api.CadDependency
import com.plm.cad.api.CadKind
import com.plm.cad.api.CadModel
import com.plm.cad.api.CadService
import com.plm.cad.api.CadTreeNode
import com.plm.cad.api.CadWhereUsed
import com.plm.cad.api.DependencyKind
import com.plm.cad.api.Transforms
import com.plm.cad.api.UpdateDependency
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private const val MAX_TREE = 20_000

internal data class DependencyRow(
    val id: UUID,
    val parentIterationId: UUID,
    val childId: UUID,
    val kind: DependencyKind,
    val required: Boolean,
    val occurrence: String?,
    val transform: List<Double>?,
    val position: Int,
)

/** Which kind of CAD document [typeId] is, by its lineage; null for anything else. */
internal fun cadKind(
    types: TypeService,
    actor: Actor,
    typeId: TypeId,
): CadKind? {
    val lineage = types.lineage(actor, typeId)
    return when {
        TypeId.CAD_ASSEMBLY in lineage -> CadKind.ASSEMBLY
        TypeId.CAD_DRAWING in lineage -> CadKind.DRAWING
        TypeId.CAD_PART in lineage -> CadKind.PART
        else -> null
    }
}

@Service
@Transactional
internal class DefaultCadService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val clock: Clock,
) : CadService {
    private val json = JsonMapper.builder().build()

    private val mapper =
        org.springframework.jdbc.core.RowMapper { rs, _ ->
            DependencyRow(
                rs.getObject("id", UUID::class.java),
                rs.getObject("parent_iteration_id", UUID::class.java),
                rs.getObject("child_master_id", UUID::class.java),
                DependencyKind.valueOf(rs.getString("kind")),
                rs.getBoolean("required"),
                rs.getString("occurrence"),
                rs.getString("transform")?.let { json.readValue(it, DoubleArray::class.java).toList() },
                rs.getInt("position"),
            )
        }

    internal fun rows(iterationIds: Collection<UUID>): List<DependencyRow> =
        if (iterationIds.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("select * from cad_dependency where parent_iteration_id in (:ids) order by position, created_at")
                .param("ids", iterationIds)
                .query(mapper)
                .list()
        }

    /** The latest (checked-in) iteration of each master. */
    private fun latestIterations(masters: Collection<UUID>): Map<UUID, UUID> =
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

    private fun objects(
        actor: Actor,
        ids: Collection<UUID>,
    ): Map<UUID, VersionedObject> =
        ids
            .distinct()
            .chunked(500)
            .flatMap { versioning.list(actor, ObjectQuery(ids = it.map(::MasterId), limit = 500)) }
            .associateBy { it.id.value }

    internal fun models(
        actor: Actor,
        ids: Collection<UUID>,
    ): Map<UUID, CadModel> {
        val found = objects(actor, ids)
        val latest = latestIterations(found.keys)
        val withDeps =
            if (latest.isEmpty()) {
                emptySet()
            } else {
                jdbc
                    .sql("select distinct parent_iteration_id from cad_dependency where parent_iteration_id in (:ids)")
                    .param("ids", latest.values)
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                    .set()
            }
        return ids.distinct().associateWith { id ->
            val o = found[id]
            CadModel(
                id,
                o != null,
                o?.number,
                o?.name,
                o?.typeId?.value,
                o?.let { cadKind(types, actor, it.typeId) },
                o?.latest?.version,
                o?.latest?.state,
                latest[id] in withDeps,
            )
        }
    }

    private fun view(
        r: DependencyRow,
        child: CadModel,
    ) = CadDependency(r.id, r.kind, r.required, r.occurrence, r.transform, r.position, child)

    private fun cadDocument(
        actor: Actor,
        id: UUID,
    ): Pair<VersionedObject, CadKind> {
        val o = versioning.get(actor, MasterId(id))
        val kind =
            cadKind(types, actor, o.typeId)
                ?: throw ValidationException("NOT_A_CAD_DOCUMENT", "${o.number} isn't a CAD document")
        return o to kind
    }

    private fun iterationOf(
        actor: Actor,
        id: UUID,
        workingCopy: Boolean,
    ): UUID =
        if (workingCopy) {
            versioning.workingCopy(actor, MasterId(id)).id.value
        } else {
            versioning
                .get(actor, MasterId(id))
                .latest.id.value
        }

    @Transactional(readOnly = true)
    override fun kindOf(
        actor: Actor,
        id: UUID,
    ): CadKind? = cadKind(types, actor, versioning.get(actor, MasterId(id)).typeId)

    @Transactional(readOnly = true)
    override fun dependencies(
        actor: Actor,
        id: UUID,
        workingCopy: Boolean,
    ): List<CadDependency> {
        val rows = rows(listOf(iterationOf(actor, id, workingCopy)))
        val children = models(actor, rows.map { it.childId })
        return rows.map { view(it, children.getValue(it.childId)) }
    }

    @Transactional(readOnly = true)
    override fun tree(
        actor: Actor,
        id: UUID,
        depth: Int,
        workingCopy: Boolean,
    ): List<CadTreeNode> {
        if (depth !in 1..30) throw ValidationException("INVALID_DEPTH", "The depth is 1 to 30")
        val rootIteration = iterationOf(actor, id, workingCopy)
        // First pass, level by level (one query per level): every iteration's dependencies.
        val dependencies = mutableMapOf<UUID, List<DependencyRow>>()
        val iterationOfMaster = mutableMapOf(id to rootIteration)
        var level = setOf(rootIteration)
        repeat(depth) {
            val todo = level - dependencies.keys
            if (todo.isEmpty()) return@repeat
            val found = rows(todo).groupBy { it.parentIterationId }
            todo.forEach { dependencies[it] = found[it].orEmpty() }
            val components =
                found.values
                    .flatten()
                    .filter { it.kind == DependencyKind.COMPONENT }
                    .map { it.childId }
            iterationOfMaster.putAll(latestIterations(components.filter { it !in iterationOfMaster }))
            level = components.mapNotNull { iterationOfMaster[it] }.toSet()
        }
        val models = models(actor, listOf(id) + dependencies.values.flatten().map { it.childId })
        // Second pass, depth first: each line followed by what it holds, placed in the root.
        val out = mutableListOf(CadTreeNode(0, null, models.getValue(id), Transforms.IDENTITY))

        fun walk(
            iteration: UUID,
            world: List<Double>,
            d: Int,
            path: Set<UUID>,
        ) {
            for (r in dependencies[iteration].orEmpty()) {
                if (out.size >= MAX_TREE) return
                val child = models.getValue(r.childId)
                val placed = Transforms.multiply(world, r.transform ?: Transforms.IDENTITY)
                out += CadTreeNode(d, view(r, child), child, placed)
                val next = iterationOfMaster[r.childId]
                if (r.kind == DependencyKind.COMPONENT &&
                    child.readable &&
                    d < depth &&
                    next != null &&
                    r.childId !in path
                ) {
                    walk(next, placed, d + 1, path + r.childId)
                }
            }
        }
        walk(rootIteration, Transforms.IDENTITY, 1, setOf(id))
        return out
    }

    @Transactional(readOnly = true)
    override fun whereUsed(
        actor: Actor,
        id: UUID,
    ): List<CadWhereUsed> {
        versioning.get(actor, MasterId(id))

        data class Use(
            val dependencyId: UUID,
            val kind: DependencyKind,
            val occurrence: String?,
            val parent: UUID,
            val version: String,
            val latest: Boolean,
        )
        val uses =
            jdbc
                .sql(
                    """
                    select d.id, d.kind, d.occurrence, i.master_id, i.revision || '.' || i.iteration as version,
                           m.latest_iteration_id = i.id as latest
                    from cad_dependency d
                    join object_iteration i on i.id = d.parent_iteration_id
                    join object_master m on m.id = i.master_id
                    where d.child_master_id = :child and i.status = 'CHECKED_IN'
                    order by i.master_id, i.seq desc
                    """.trimIndent(),
                ).param("child", id)
                .query { rs, _ ->
                    Use(
                        rs.getObject("id", UUID::class.java),
                        DependencyKind.valueOf(rs.getString("kind")),
                        rs.getString("occurrence"),
                        rs.getObject("master_id", UUID::class.java),
                        rs.getString("version"),
                        rs.getBoolean("latest"),
                    )
                }.list()
        val parents = models(actor, uses.map { it.parent })
        return uses
            .filter { parents.getValue(it.parent).readable }
            .map {
                CadWhereUsed(
                    it.dependencyId,
                    it.kind,
                    it.occurrence,
                    parents.getValue(it.parent),
                    it.version,
                    it.latest,
                )
            }
    }

    private fun checkTransform(t: List<Double>?) {
        if (t != null && (t.size != 16 || t.any { !it.isFinite() })) {
            throw ValidationException(
                "INVALID_DEPENDENCY",
                "A placement is 16 numbers (a 4x4 matrix, column by column)",
            )
        }
    }

    /** Whether [from] (through the latest versions' components and references) reaches [target]. */
    private fun reaches(
        from: UUID,
        target: UUID,
    ): Boolean =
        jdbc
            .sql(
                """
                with recursive walk (master_id) as (
                    select cast(:from as uuid)
                    union
                    select d.child_master_id from walk w
                    join object_master m on m.id = w.master_id
                    join cad_dependency d on d.parent_iteration_id = m.latest_iteration_id
                )
                select exists (select 1 from walk where master_id = :target)
                """.trimIndent(),
            ).param("from", from)
            .param("target", target)
            .query(Boolean::class.java)
            .single()

    override fun add(
        actor: Actor,
        parentId: UUID,
        request: AddDependency,
    ): CadDependency {
        val (parent, parentKind) = cadDocument(actor, parentId)
        val wc = versioning.workingCopy(actor, parent.id).id.value
        val (child, childKind) = cadDocument(actor, request.childId)

        fun bad(message: String): Nothing = throw ValidationException("INVALID_DEPENDENCY", message)
        when (request.kind) {
            DependencyKind.COMPONENT -> {
                if (parentKind != CadKind.ASSEMBLY) bad("Only assemblies have components")
                if (childKind == CadKind.DRAWING) bad("A drawing can't be a component")
            }
            DependencyKind.DRAWING -> {
                if (parentKind != CadKind.DRAWING) bad("Only drawings show models")
                if (childKind == CadKind.DRAWING) bad("A drawing shows models, not drawings")
            }
            DependencyKind.REFERENCE -> {}
        }
        if (child.id == parent.id || reaches(child.id.value, parent.id.value)) {
            throw ConflictException(
                "CYCLE",
                "${child.number} depends on ${parent.number}: it can't depend on it in turn",
            )
        }
        checkTransform(request.transform)
        val position =
            jdbc
                .sql("select coalesce(max(position), 0) + 10 from cad_dependency where parent_iteration_id = :p")
                .param("p", wc)
                .query(Int::class.java)
                .single()
        val id = UuidV7.generate(clock)
        jdbc
            .sql(
                """
                insert into cad_dependency (id, tenant_id, parent_iteration_id, child_master_id, kind, required, occurrence,
                    transform, position, created_at, created_by)
                values (:id, :t, :p, :c, :kind, :required, :occurrence, cast(:transform as jsonb), :position, :at, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("t", actor.tenant.value)
            .param("p", wc)
            .param("c", child.id.value)
            .param("kind", request.kind.name)
            .param("required", request.required)
            .param("occurrence", request.occurrence?.trim()?.ifEmpty { null })
            .param("transform", request.transform?.let { json.writeValueAsString(it) })
            .param("position", position)
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return dependencies(actor, parentId, workingCopy = true).first { it.id == id }
    }

    override fun update(
        actor: Actor,
        parentId: UUID,
        dependencyId: UUID,
        request: UpdateDependency,
    ): CadDependency {
        cadDocument(actor, parentId)
        val wc = versioning.workingCopy(actor, MasterId(parentId)).id.value
        checkTransform(request.transform)
        val n =
            jdbc
                .sql(
                    """
                    update cad_dependency set required = :required, occurrence = :occurrence, transform = cast(:transform as jsonb)
                    where id = :id and parent_iteration_id = :p
                    """.trimIndent(),
                ).param("required", request.required)
                .param("occurrence", request.occurrence?.trim()?.ifEmpty { null })
                .param("transform", request.transform?.let { json.writeValueAsString(it) })
                .param("id", dependencyId)
                .param("p", wc)
                .update()
        if (n == 0) throw NotFoundException("DEPENDENCY_NOT_FOUND", "No such dependency on the working copy")
        return dependencies(actor, parentId, workingCopy = true).first { it.id == dependencyId }
    }

    override fun remove(
        actor: Actor,
        parentId: UUID,
        dependencyId: UUID,
    ) {
        cadDocument(actor, parentId)
        val wc = versioning.workingCopy(actor, MasterId(parentId)).id.value
        val n =
            jdbc
                .sql("delete from cad_dependency where id = :id and parent_iteration_id = :p")
                .param("id", dependencyId)
                .param("p", wc)
                .update()
        if (n == 0) throw NotFoundException("DEPENDENCY_NOT_FOUND", "No such dependency on the working copy")
    }
}
