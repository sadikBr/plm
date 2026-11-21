package com.plm.bom.domain

import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.FlatLine
import com.plm.bom.api.RollupAttribute
import com.plm.bom.api.RollupNode
import com.plm.bom.api.RollupResult
import com.plm.bom.api.RollupService
import com.plm.bom.api.StructureNode
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.types.api.AttributeService
import com.plm.types.api.DataType
import com.plm.types.api.EnumerationService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.MathContext
import java.util.UUID

@Service
@Transactional(readOnly = true)
internal class DefaultRollupService(
    private val bom: BomService,
    private val versioning: VersioningService,
    private val attributes: AttributeService,
    private val enumerations: EnumerationService,
    private val jdbc: JdbcClient,
) : RollupService {
    private val json = JsonMapper.builder().build()
    private val numeric = setOf(DataType.INTEGER, DataType.REAL, DataType.QUANTITY)

    override fun attributes(
        actor: Actor,
        partId: MasterId,
    ): List<RollupAttribute> {
        val part = versioning.get(actor, partId)
        return attributes
            .effective(actor, part.typeId)
            .filter { it.dataType in numeric && !it.multiValued }
            .map { RollupAttribute(it.name, it.labels, it.dataType.name, it.dimension) }
    }

    /** Attributes of these iterations, by iteration. */
    private fun attrs(iterations: Collection<UUID>): Map<UUID, Map<String, Any?>> {
        if (iterations.isEmpty()) return emptyMap()
        return jdbc
            .sql("select id, attrs from object_iteration where id = any(cast(:ids as uuid[]))")
            .param("ids", iterations.toTypedArray())
            .query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                rs.getObject("id", UUID::class.java) to
                    (json.readValue(rs.getString("attrs"), Map::class.java) as Map<String, Any?>)
            }.list()
            .toMap()
    }

    override fun rollup(
        actor: Actor,
        partId: MasterId,
        attribute: String,
        spec: ConfigSpec,
    ): RollupResult {
        val part = versioning.get(actor, partId)
        val def =
            attributes.effective(actor, part.typeId).firstOrNull { it.name == attribute && it.dataType in numeric }
                ?: throw ValidationException("INVALID_ROLLUP", "$attribute isn't a number this part has")
        val structure = bom.structure(actor, partId, spec, depth = 50)
        val units = enumerations.units(actor).associateBy { it.code }
        val baseUnit =
            def.dimension?.let { d ->
                units.values
                    .firstOrNull {
                        it.dimension == d &&
                            it.factor.compareTo(BigDecimal.ONE) == 0 &&
                            it.offset.signum() == 0
                    }?.code
            }

        fun valueOf(raw: Any?): BigDecimal? =
            when {
                raw == null -> null
                raw is Number -> BigDecimal(raw.toString())
                raw is Map<*, *> -> {
                    val v = (raw["value"] as? Number)?.let { BigDecimal(it.toString()) }
                    val u = units[raw["unit"] as? String]
                    if (v == null || u == null) null else v.multiply(u.factor).add(u.offset)
                }
                else -> raw.toString().toBigDecimalOrNull()
            }
        val iterations =
            structure.nodes.mapNotNull { it.line.child.iterationId } + listOfNotNull(structure.rootIterationId)
        val values = attrs(iterations.distinct()).mapValues { (_, a) -> valueOf(a[attribute]) }

        // Bottom-up: children first.
        val children = structure.nodes.groupBy { it.parentPath }
        val rolled = HashMap<String, BigDecimal?>()
        val missingParts = HashSet<UUID>()

        fun roll(n: StructureNode): BigDecimal? {
            rolled[n.path]?.let { return it }
            val own =
                n.line.child.iterationId
                    ?.let { values[it] }
            val kids = children[n.path].orEmpty()
            val result =
                if (kids.isEmpty()) {
                    if (own == null && n.line.child.readable) missingParts += n.line.child.id
                    own
                } else {
                    kids.fold(own ?: BigDecimal.ZERO) { acc, k -> acc + (roll(k) ?: BigDecimal.ZERO) * k.line.quantity }
                }
            rolled[n.path] = result
            return result
        }
        val top = children[null].orEmpty()
        val rootOwn = structure.rootIterationId?.let { values[it] }
        val total =
            top.fold(rootOwn ?: BigDecimal.ZERO) { acc, n ->
                acc +
                    (roll(n) ?: BigDecimal.ZERO) * n.line.quantity
            }

        val extended = HashMap<String, BigDecimal>()
        val nodes =
            structure.nodes.map { n ->
                val ext = (n.parentPath?.let { extended[it] } ?: BigDecimal.ONE) * n.line.quantity
                extended[n.path] = ext
                val r = rolled[n.path] ?: roll(n)
                RollupNode(
                    n.path,
                    n.level,
                    n.line.child,
                    ext.clean(),
                    n.line.child.iterationId
                        ?.let { values[it] }
                        ?.clean(),
                    r?.clean(),
                    r?.let { (it * n.line.quantity).clean() },
                    n.line.child.id in missingParts,
                )
            }
        return RollupResult(
            attribute,
            if (def.dataType ==
                DataType.QUANTITY
            ) {
                baseUnit
            } else {
                null
            },
            total.clean(),
            missingParts.size,
            nodes,
        )
    }

    override fun flatten(
        actor: Actor,
        partId: MasterId,
        spec: ConfigSpec,
    ): List<FlatLine> {
        val structure = bom.structure(actor, partId, spec, depth = 50)
        val extended = HashMap<String, BigDecimal>()

        data class Acc(
            var total: BigDecimal,
            var lines: Int,
            val levels: MutableSet<Int>,
        )
        val acc = LinkedHashMap<Pair<UUID, String>, Pair<com.plm.bom.api.UsedPart, Acc>>()
        for (n in structure.nodes) {
            val ext = (n.parentPath?.let { extended[it] } ?: BigDecimal.ONE) * n.line.quantity
            extended[n.path] = ext
            val key = n.line.child.id to n.line.unit
            val entry = acc.getOrPut(key) { n.line.child to Acc(BigDecimal.ZERO, 0, sortedSetOf()) }
            entry.second.total += ext
            entry.second.lines++
            entry.second.levels += n.level
        }
        return acc
            .map { (key, v) ->
                FlatLine(v.first, key.second, v.second.total.clean(), v.second.lines, v.second.levels.sorted())
            }.sortedWith(compareBy({ it.part.number ?: "~" }, { it.unit }))
    }

    private fun BigDecimal.clean(): BigDecimal =
        round(MathContext.DECIMAL64).stripTrailingZeros().let {
            if (it.scale() <
                0
            ) {
                it.setScale(0)
            } else {
                it
            }
        }
}
