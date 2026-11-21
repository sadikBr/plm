package com.plm.classification.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.Permission
import com.plm.classification.api.AttributeMatch
import com.plm.classification.api.ClassAttribute
import com.plm.classification.api.ClassDataType
import com.plm.classification.api.ClassFilter
import com.plm.classification.api.ClassSearchResult
import com.plm.classification.api.ClassSearchService
import com.plm.classification.api.ClassifiedPart
import com.plm.classification.api.SimilarPart
import com.plm.classification.api.SimilarParts
import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

@Service
@Transactional(readOnly = true)
internal class DefaultClassSearchService(
    private val jdbc: JdbcClient,
    private val classes: DefaultClassificationService,
    private val access: AccessService,
) : ClassSearchService {
    /** The classified latest versions the actor can read, in [classIds], matching [extra] SQL. */
    private fun query(
        actor: Actor,
        classIds: Collection<UUID>,
        extra: String = "true",
        params: Map<String, Any?> = emptyMap(),
        limit: Int,
    ): List<ClassifiedPart> {
        val readable =
            access.filter(
                actor,
                Permission.READ,
                AccessColumns("m.context_id", "m.type_id", "i.state", "m.id"),
            )
        val names = classes.tree(actor).associate { it.id to it.name }
        return jdbc
            .sql(
                """
                select m.id, m.number, m.name, i.revision, i.iteration, i.state, cv.class_id, cv."values"
                from class_value cv
                join object_master m on m.latest_iteration_id = cv.iteration_id
                join object_iteration i on i.id = m.latest_iteration_id
                where cv.tenant_id = :t and cv.class_id = any(cast(:classes as uuid[])) and ${readable.sql} and ($extra)
                order by m.number limit :limit
                """.trimIndent(),
            ).param("t", actor.tenant.value)
            .param("classes", classIds.toTypedArray())
            .params(readable.params)
            .params(params)
            .param("limit", limit)
            .query { rs, _ ->
                val classId = rs.getObject("class_id", UUID::class.java)
                ClassifiedPart(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("number"),
                    rs.getString("name"),
                    "${rs.getString("revision")}.${rs.getInt("iteration")}",
                    rs.getString("state"),
                    classId,
                    names[classId] ?: "",
                    classes.json.readValue(rs.getString("values"), classes.mapType),
                )
            }.list()
    }

    /** [classId] and every class below it. */
    private fun subtree(
        actor: Actor,
        classId: UUID,
    ): Set<UUID> {
        val tree = classes.tree(actor)
        if (tree.none { it.id == classId }) throw NotFoundException("CLASS_NOT_FOUND", "There's no such class")
        return tree.filter { n -> classes.path(tree, n.id).any { it.id == classId } }.map { it.id }.toSet()
    }

    override fun search(
        actor: Actor,
        classId: UUID,
        filters: List<ClassFilter>,
        limit: Int,
    ): ClassSearchResult {
        val attributes = classes.attributes(actor, classId)
        val byKey = attributes.associateBy { it.key }
        val clauses = ArrayList<String>()
        val params = HashMap<String, Any?>()
        filters.forEachIndexed { i, f ->
            val a =
                byKey[f.key] ?: throw ValidationException("INVALID_FILTER", "${f.key} isn't an attribute of this class")
            // The key is one of the class's, checked against a fixed pattern: safe to put in the SQL.
            val v = "cv.\"values\"->>'${a.key}'"
            when (a.dataType) {
                ClassDataType.NUMBER -> {
                    f.min?.let {
                        clauses += "($v)::numeric >= :min$i"
                        params["min$i"] = it
                    }
                    f.max?.let {
                        clauses += "($v)::numeric <= :max$i"
                        params["max$i"] = it
                    }
                    if (f.min == null &&
                        f.max == null
                    ) {
                        throw ValidationException("INVALID_FILTER", "Give ${a.label} a minimum or a maximum")
                    }
                }
                ClassDataType.TEXT -> {
                    clauses += "$v ilike :v$i"
                    params["v$i"] = "%" + (f.value ?: "").replace("%", "\\%").replace("_", "\\_") + "%"
                }
                else -> {
                    clauses += "$v = :v$i"
                    params["v$i"] = f.value ?: throw ValidationException("INVALID_FILTER", "Give ${a.label} a value")
                }
            }
        }
        val bounded = limit.coerceIn(1, 1000)
        val rows =
            query(
                actor,
                subtree(actor, classId),
                clauses.joinToString(" and ").ifEmpty { "true" },
                params,
                bounded + 1,
            )
        return ClassSearchResult(classId, attributes, rows.take(bounded), rows.size > bounded)
    }

    /** How close [b] is to [a] for attribute [attr]: 1 the same, falling to 0 at twice apart. */
    private fun match(
        attr: ClassAttribute,
        a: Any?,
        b: Any?,
    ): Double {
        if (a == null || b == null) return 0.0
        if (attr.dataType !=
            ClassDataType.NUMBER
        ) {
            return if (a.toString().equals(b.toString(), ignoreCase = true)) 1.0 else 0.0
        }
        val x = BigDecimal(a.toString()).toDouble()
        val y = BigDecimal(b.toString()).toDouble()
        val scale = max(abs(x), abs(y))
        if (scale == 0.0) return 1.0
        return (1.0 - abs(x - y) / scale).coerceIn(0.0, 1.0)
    }

    override fun similar(
        actor: Actor,
        partId: UUID,
        limit: Int,
    ): SimilarParts {
        val mine = classes.classification(actor, partId)
        val classId =
            mine.path.lastOrNull()?.id ?: return SimilarParts(partId, null, emptyList(), emptyMap(), emptyList())
        val compared = mine.attributes.filter { mine.values[it.key] != null }
        val candidates = query(actor, listOf(classId), "m.id <> :me", mapOf("me" to partId), 5000)
        val ranked =
            candidates
                .map { c ->
                    val matches =
                        compared.map {
                            AttributeMatch(
                                it.key,
                                match(it, mine.values[it.key], c.values[it.key]),
                            )
                        }
                    SimilarPart(c, if (matches.isEmpty()) 0.0 else matches.sumOf { it.score } / matches.size, matches)
                }.sortedWith(compareByDescending<SimilarPart> { it.score }.thenBy { it.part.number })
                .take(limit.coerceIn(1, 100))
        return SimilarParts(partId, classId, mine.attributes, mine.values, ranked)
    }
}
