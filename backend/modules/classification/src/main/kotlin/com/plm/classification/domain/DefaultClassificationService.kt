package com.plm.classification.domain

import com.plm.classification.api.ClassAttribute
import com.plm.classification.api.ClassDataType
import com.plm.classification.api.ClassNode
import com.plm.classification.api.ClassificationService
import com.plm.classification.api.PartClassification
import com.plm.classification.api.SaveClass
import com.plm.classification.api.SaveClassAttribute
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private val CODE = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,39}$")
private val KEY = Regex("^[a-z][a-zA-Z0-9_]{0,39}$")

internal fun badClass(message: String): Nothing = throw ValidationException("INVALID_CLASS", message)

@Service
@Transactional
internal class DefaultClassificationService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val clock: Clock,
) : ClassificationService {
    internal val json: JsonMapper = JsonMapper.builder().build()
    internal val mapType = object : TypeReference<Map<String, Any?>>() {}

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ACCESS_DENIED",
                "Only site administrators change the class tree",
            )
        }
    }

    private fun attributeRows(
        where: String,
        params: Map<String, Any?>,
    ): List<ClassAttribute> =
        jdbc
            .sql("select * from class_attribute where $where order by position, key")
            .params(params)
            .query { rs, _ ->
                ClassAttribute(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("class_id", UUID::class.java),
                    rs.getString("key"),
                    rs.getString("label"),
                    ClassDataType.valueOf(rs.getString("data_type")),
                    rs.getString("unit"),
                    (rs.getArray("allowed").array as Array<*>).map { it.toString() },
                    rs.getBoolean("required"),
                    rs.getInt("position"),
                )
            }.list()

    @Transactional(readOnly = true)
    override fun tree(actor: Actor): List<ClassNode> {
        val attrs =
            attributeRows(
                "class_id in (select id from class_node where tenant_id = :t)",
                mapOf(
                    "t" to actor.tenant.value,
                ),
            ).groupBy { it.classId }
        val parts =
            jdbc
                .sql(
                    """
                    select cv.class_id, count(*) from class_value cv join object_master m on m.latest_iteration_id = cv.iteration_id
                    where cv.tenant_id = :t group by cv.class_id
                    """.trimIndent(),
                ).param("t", actor.tenant.value)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }
                .list()
                .toMap()
        return jdbc
            .sql("select * from class_node where tenant_id = :t order by position, name")
            .param("t", actor.tenant.value)
            .query { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                ClassNode(
                    id,
                    rs.getObject("parent_id", UUID::class.java),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getInt("position"),
                    attrs[id].orEmpty(),
                    parts[id] ?: 0,
                )
            }.list()
    }

    private fun node(
        actor: Actor,
        id: UUID,
    ): ClassNode =
        tree(actor).firstOrNull { it.id == id } ?: throw NotFoundException("CLASS_NOT_FOUND", "There's no such class")

    /** [id] and its ancestors, root first. */
    internal fun path(
        tree: List<ClassNode>,
        id: UUID,
    ): List<ClassNode> {
        val byId = tree.associateBy { it.id }
        val out = ArrayList<ClassNode>()
        var at = byId[id]
        while (at != null && out.size < 50) {
            out.add(0, at)
            at = at.parentId?.let { byId[it] }
        }
        return out
    }

    private fun check(
        actor: Actor,
        request: SaveClass,
        self: UUID?,
    ) {
        if (!CODE.matches(
                request.code.trim(),
            )
        ) {
            badClass("'${request.code}' isn't a class code: letters, digits, . _ -, at most 40")
        }
        if (request.name.isBlank() || request.name.length > 200) badClass("A name is 1 to 200 characters")
        val tree = tree(actor)
        tree.firstOrNull { it.code.equals(request.code.trim(), ignoreCase = true) && it.id != self }?.let {
            throw ConflictException("DUPLICATE_CODE", "${it.code} is already the code of ${it.name}")
        }
        request.parentId?.let { parent ->
            if (tree.none { it.id == parent }) {
                throw NotFoundException(
                    "CLASS_NOT_FOUND",
                    "There's no such parent class",
                )
            }
            if (self != null && path(tree, parent).any { it.id == self }) badClass("A class can't be under itself")
        }
    }

    override fun createClass(
        actor: Actor,
        request: SaveClass,
    ): ClassNode {
        requireAdmin(actor)
        check(actor, request, null)
        val id = UuidV7.generate(clock)
        jdbc
            .sql(
                """
                insert into class_node (id, tenant_id, parent_id, code, name, description, position, created_at)
                values (:id, :t, :p, :c, :n, :d, coalesce((select max(position) from class_node where parent_id is not distinct from :p and tenant_id = :t), 0) + 10, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("t", actor.tenant.value)
            .param("p", request.parentId)
            .param("c", request.code.trim())
            .param("n", request.name.trim())
            .param("d", request.description?.trim()?.takeIf { it.isNotEmpty() })
            .param("now", Timestamp.from(clock.instant()))
            .update()
        return node(actor, id)
    }

    override fun updateClass(
        actor: Actor,
        classId: UUID,
        request: SaveClass,
    ): ClassNode {
        requireAdmin(actor)
        node(actor, classId)
        check(actor, request, classId)
        jdbc
            .sql("update class_node set parent_id = :p, code = :c, name = :n, description = :d where id = :id")
            .param("id", classId)
            .param("p", request.parentId)
            .param("c", request.code.trim())
            .param("n", request.name.trim())
            .param("d", request.description?.trim()?.takeIf { it.isNotEmpty() })
            .update()
        return node(actor, classId)
    }

    override fun deleteClass(
        actor: Actor,
        classId: UUID,
    ) {
        requireAdmin(actor)
        val tree = tree(actor)
        node(actor, classId)
        if (tree.any {
                it.parentId == classId
            }
        ) {
            throw ConflictException("CLASS_IN_USE", "It has classes under it: move or delete them first")
        }
        val used =
            jdbc
                .sql(
                    "select count(*) from class_value where class_id = :c",
                ).param("c", classId)
                .query(Int::class.java)
                .single()
        if (used > 0) throw ConflictException("CLASS_IN_USE", "$used part version(s) are classified in it")
        jdbc.sql("delete from class_node where id = :id").param("id", classId).update()
    }

    override fun addAttribute(
        actor: Actor,
        classId: UUID,
        request: SaveClassAttribute,
    ): ClassNode {
        requireAdmin(actor)
        node(actor, classId)
        val key = request.key.trim()
        if (!KEY.matches(key)) badClass("'$key' isn't an attribute key: camelCase letters and digits, at most 40")
        if (request.label.isBlank()) badClass("Give the attribute a label")
        val allowed =
            request.allowed
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        if (request.dataType == ClassDataType.ENUM && allowed.isEmpty()) badClass("List the values it may take")
        // Keys are unique along the path, and in the classes below.
        val tree = tree(actor)
        val related = (path(tree, classId) + tree.filter { n -> path(tree, n.id).any { it.id == classId } }).toSet()
        related.flatMap { it.attributes }.firstOrNull { it.key == key }?.let { a ->
            throw ConflictException(
                "DUPLICATE_KEY",
                "$key is already an attribute of ${tree.first { it.id == a.classId }.name}",
            )
        }
        jdbc
            .sql(
                """
                insert into class_attribute (id, class_id, key, label, data_type, unit, allowed, required, position)
                values (:id, :c, :k, :l, :dt, :u, :a, :r, coalesce((select max(position) from class_attribute where class_id = :c), 0) + 10)
                """.trimIndent(),
            ).param("id", UuidV7.generate(clock))
            .param("c", classId)
            .param("k", key)
            .param("l", request.label.trim())
            .param("dt", request.dataType.name)
            .param("u", request.unit?.trim()?.takeIf { it.isNotEmpty() })
            .param("a", allowed.toTypedArray())
            .param("r", request.required)
            .update()
        return node(actor, classId)
    }

    override fun removeAttribute(
        actor: Actor,
        attributeId: UUID,
    ): ClassNode {
        requireAdmin(actor)
        val classId =
            jdbc
                .sql("select class_id from class_attribute where id = :id")
                .param("id", attributeId)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .optional()
                .orElseThrow { NotFoundException("ATTRIBUTE_NOT_FOUND", "There's no such attribute") }
        // Values already given stay in the versions that have them; new ones can't be.
        jdbc.sql("delete from class_attribute where id = :id").param("id", attributeId).update()
        return node(actor, classId)
    }

    @Transactional(readOnly = true)
    override fun attributes(
        actor: Actor,
        classId: UUID,
    ): List<ClassAttribute> {
        val tree = tree(actor)
        if (tree.none { it.id == classId }) throw NotFoundException("CLASS_NOT_FOUND", "There's no such class")
        return path(tree, classId).flatMap { it.attributes }
    }

    private fun requirePart(
        actor: Actor,
        partId: UUID,
    ) = versioning.get(actor, MasterId(partId)).also {
        if (TypeId.PART !in
            types.lineage(actor, it.typeId)
        ) {
            badClass("${it.number} isn't a part: only parts are classified")
        }
    }

    @Transactional(readOnly = true)
    override fun classification(
        actor: Actor,
        partId: UUID,
        workingCopy: Boolean,
    ): PartClassification {
        val o = requirePart(actor, partId)
        val it = if (workingCopy) versioning.workingCopy(actor, o.id) else o.latest
        return read(actor, partId, it.id.value, it.version)
    }

    private fun read(
        actor: Actor,
        partId: UUID,
        iteration: UUID,
        version: String,
    ): PartClassification {
        val row =
            jdbc
                .sql("select class_id, \"values\", updated_at, updated_by from class_value where iteration_id = :i")
                .param("i", iteration)
                .query { rs, _ ->
                    listOf(
                        rs.getObject(1, UUID::class.java),
                        json.readValue(rs.getString(2), mapType),
                        rs.getTimestamp(3).toInstant(),
                        rs.getString(4),
                    )
                }.optional()
                .orElse(null)
                ?: return PartClassification(partId, version, emptyList(), emptyList(), emptyMap(), null, null)
        val tree = tree(actor)
        val path = path(tree, row[0] as UUID)
        @Suppress("UNCHECKED_CAST")
        return PartClassification(
            partId,
            version,
            path,
            path.flatMap { it.attributes },
            row[1] as Map<String, Any?>,
            row[2] as java.time.Instant,
            row[3] as String,
        )
    }

    /** Values checked and normalized against [attributes]: numbers as numbers, unknown keys refused. */
    internal fun validate(
        attributes: List<ClassAttribute>,
        values: Map<String, Any?>,
    ): Map<String, Any?> {
        fun bad(message: String): Nothing = throw ValidationException("INVALID_CLASS_VALUES", message)
        val byKey = attributes.associateBy { it.key }
        values.keys.firstOrNull { it !in byKey }?.let { bad("$it isn't an attribute of this class") }
        val out = LinkedHashMap<String, Any?>()
        for (a in attributes) {
            val raw = values[a.key]?.takeUnless { it is String && it.isBlank() }
            if (raw == null) {
                if (a.required) bad("${a.label} is required")
                continue
            }
            out[a.key] =
                when (a.dataType) {
                    ClassDataType.NUMBER ->
                        (raw as? Number)?.let { BigDecimal(it.toString()) }
                            ?: raw.toString().trim().toBigDecimalOrNull()
                            ?: bad("${a.label} is a number")
                    ClassDataType.BOOLEAN ->
                        when (raw.toString().lowercase()) {
                            "true", "yes" -> true
                            "false", "no" -> false
                            else -> bad("${a.label} is yes or no")
                        }
                    ClassDataType.ENUM ->
                        raw.toString().trim().takeIf { it in a.allowed }
                            ?: bad("${a.label} is one of ${a.allowed.joinToString(", ")}")
                    ClassDataType.TEXT -> raw.toString().trim().take(1000)
                }
        }
        return out
    }

    override fun classify(
        actor: Actor,
        partId: UUID,
        classId: UUID?,
        values: Map<String, Any?>,
    ): PartClassification {
        val o = requirePart(actor, partId)
        val wc = versioning.workingCopy(actor, o.id)
        if (classId == null) {
            jdbc.sql("delete from class_value where iteration_id = :i").param("i", wc.id.value).update()
            return read(actor, partId, wc.id.value, wc.version)
        }
        val checked = validate(attributes(actor, classId), values)
        jdbc
            .sql(
                """
                insert into class_value (iteration_id, tenant_id, master_id, class_id, "values", updated_at, updated_by)
                values (:i, :t, :m, :c, cast(:v as jsonb), :now, :by)
                on conflict (iteration_id) do update set class_id = excluded.class_id, "values" = excluded."values",
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("i", wc.id.value)
            .param("t", actor.tenant.value)
            .param("m", partId)
            .param("c", classId)
            .param("v", json.writeValueAsString(checked))
            .param("now", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return read(actor, partId, wc.id.value, wc.version)
    }
}

/** A part's classification follows it into the next iteration (check-out, revise, one-offs). */
@Component
internal class ClassificationFollowsIterations(
    private val jdbc: JdbcClient,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "versioning.ObjectCheckedOut.v1" &&
            envelope.eventType != "versioning.ObjectRevised.v1"
        ) {
            return
        }
        val iteration = UUID.fromString(envelope.payload["iterationId"].toString())
        jdbc
            .sql(
                """
                insert into class_value (iteration_id, tenant_id, master_id, class_id, "values", updated_at, updated_by)
                select :it, cv.tenant_id, cv.master_id, cv.class_id, cv."values", cv.updated_at, cv.updated_by
                from class_value cv join object_iteration i on i.based_on_id = cv.iteration_id where i.id = :it
                on conflict do nothing
                """.trimIndent(),
            ).param("it", iteration)
            .update()
    }
}
