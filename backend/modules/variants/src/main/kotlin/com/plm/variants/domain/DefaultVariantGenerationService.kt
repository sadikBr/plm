package com.plm.variants.domain

import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.platform.actor.Actor
import com.plm.variants.api.ConfiguredLine
import com.plm.variants.api.GeneratedPart
import com.plm.variants.api.GenerationResult
import com.plm.variants.api.VariantGenerationService
import com.plm.variants.api.VariantOrigin
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultVariantGenerationService(
    private val jdbc: JdbcClient,
    private val specs: DefaultVariantSpecService,
    private val conditions: DefaultConditionService,
    private val versioning: VersioningService,
    private val bom: BomService,
    private val clock: Clock,
) : VariantGenerationService {
    private val json = JsonMapper.builder().build()
    private val mapType = object : TypeReference<Map<String, String>>() {}

    override fun generate(
        actor: Actor,
        specId: UUID,
    ): GenerationResult {
        val spec = specs.get(actor, specId)
        if (!spec.check.valid) invalidSelection(spec.check)
        val structure = conditions.configured(actor, spec.productId, spec.selection, spec.spec)
        val children = structure.lines.groupBy { it.node.parentPath }
        val done = LinkedHashMap<String, GeneratedPart>()

        /** The options anything at or below [path] depends on (null: the top). */
        fun dependsOn(path: String?): Set<String> =
            children[path]
                .orEmpty()
                .flatMap { l ->
                    (l.condition?.let { Condition.parse(it).options() }.orEmpty()) + dependsOn(l.node.path)
                }.toSet()

        fun make(
            baseId: UUID,
            baseIteration: UUID,
            path: String?,
            top: Boolean,
        ): GeneratedPart {
            val base = versioning.get(actor, MasterId(baseId))
            val options = dependsOn(path)
            if (options.isEmpty() && !top) {
                return GeneratedPart(base.id.value, base.number, base.name, base.id.value, base.number, "AS_IS")
            }
            val chosen = spec.selection.filterKeys { it in options }.toSortedMap()
            val signature = chosen.entries.joinToString(",") { "${it.key}=${it.value}" }
            done[signature + baseIteration]?.let { return it }
            val existing =
                jdbc
                    .sql(
                        """
                        select variant_id from variant_part where base_id = :b and base_iteration = :i and signature = :s
                        order by created_at limit 1
                        """.trimIndent(),
                    ).param("b", baseId)
                    .param("i", baseIteration)
                    .param("s", signature)
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                    .optional()
                    .orElse(null)
                    ?.let { id ->
                        versioning.list(actor, ObjectQuery(ids = listOf(MasterId(id)), limit = 1)).firstOrNull()
                    }
            if (existing != null) {
                return GeneratedPart(
                    existing.id.value,
                    existing.number,
                    existing.name,
                    base.id.value,
                    base.number,
                    "REUSED",
                ).also { done[signature + baseIteration] = it }
            }
            // Children first: the variant's lines point at theirs.
            val lines: List<Pair<ConfiguredLine, GeneratedPart>> =
                children[path].orEmpty().filter { it.applies }.map { l ->
                    val child = l.node.line.child
                    val made =
                        if (child.iterationId != null && child.hasChildren) {
                            make(child.id, child.iterationId!!, l.node.path, top = false)
                        } else {
                            GeneratedPart(
                                child.id,
                                child.number ?: "",
                                child.name ?: "",
                                child.id,
                                child.number ?: "",
                                "AS_IS",
                            )
                        }
                    l to made
                }
            val label = if (top) spec.name else chosen.values.joinToString(", ")
            val variant =
                versioning.create(
                    actor,
                    CreateVersionedObject(base.typeId, base.contextId, "${base.name} – $label".take(200)),
                )
            versioning.checkOut(actor, variant.id)
            for ((l, made) in lines) {
                val line = l.node.line
                bom.add(
                    actor,
                    variant.id,
                    AddUsage(made.id, line.quantity, line.unit, line.lineNumber, line.findNumber, line.notes),
                )
            }
            versioning.checkIn(
                actor,
                variant.id,
                "Variant of ${base.number} for ${signature.ifEmpty { "its choices" }}",
            )
            jdbc
                .sql(
                    """
                    insert into variant_part (variant_id, tenant_id, base_id, base_iteration, signature, selection, spec_id, created_at, created_by)
                    values (:v, :t, :b, :i, :s, cast(:sel as jsonb), :spec, :now, :by)
                    """.trimIndent(),
                ).param("v", variant.id.value)
                .param("t", actor.tenant.value)
                .param("b", baseId)
                .param("i", baseIteration)
                .param("s", signature)
                .param("sel", json.writeValueAsString(chosen))
                .param("spec", if (top) spec.id else null)
                .param("now", Timestamp.from(clock.instant()))
                .param("by", actor.principal.value)
                .update()
            return GeneratedPart(variant.id.value, variant.number, variant.name, base.id.value, base.number, "CREATED")
                .also { done[signature + baseIteration] = it }
        }

        val rootIteration = structure.rootIterationId ?: invalidSelection(spec.check)
        val all = ArrayList<GeneratedPart>()
        val top = make(spec.productId, rootIteration, null, top = true)
        all += done.values.filter { it.id != top.id } + top
        val parts = all.distinctBy { it.id }
        return GenerationResult(
            top,
            parts,
            parts.count { it.outcome == "CREATED" },
            parts.count {
                it.outcome ==
                    "REUSED"
            },
        )
    }

    private fun origins(
        actor: Actor,
        where: String,
        params: Map<String, Any?>,
    ): List<VariantOrigin> {
        val rows =
            jdbc
                .sql(
                    """
                    select vp.*, vs.name as spec_name,
                           (select i.revision || '.' || i.iteration from object_iteration i where i.id = vp.base_iteration) as base_version
                    from variant_part vp left join variant_spec vs on vs.id = vp.spec_id where $where order by vp.created_at
                    """.trimIndent(),
                ).params(params)
                .query { rs, _ ->
                    VariantOrigin(
                        rs.getObject("variant_id", UUID::class.java),
                        rs.getObject("base_id", UUID::class.java),
                        null,
                        null,
                        rs.getString("base_version"),
                        json.readValue(rs.getString("selection"), mapType),
                        rs.getObject("spec_id", UUID::class.java),
                        rs.getString("spec_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("created_by"),
                    )
                }.list()
        val ids = (rows.map { it.baseId } + rows.map { it.variantId }).distinct().map { MasterId(it) }
        val readable =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                versioning
                    .list(
                        actor,
                        ObjectQuery(ids = ids, limit = ids.size),
                    ).associateBy {
                        it.id.value
                    }
            }
        return rows.filter { it.variantId in readable }.map { o ->
            readable[o.baseId]?.let { o.copy(baseNumber = it.number, baseName = it.name) } ?: o
        }
    }

    @Transactional(readOnly = true)
    override fun origin(
        actor: Actor,
        partId: UUID,
    ): VariantOrigin? {
        versioning.get(actor, MasterId(partId))
        return origins(actor, "vp.variant_id = :v", mapOf("v" to partId)).singleOrNull()
    }

    @Transactional(readOnly = true)
    override fun variantsOf(
        actor: Actor,
        baseId: UUID,
    ): List<VariantOrigin> {
        versioning.get(actor, MasterId(baseId))
        return origins(actor, "vp.base_id = :b", mapOf("b" to baseId))
    }
}
