package com.plm.types.infra

import com.plm.platform.error.ConflictException
import com.plm.platform.id.TenantId
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeId
import com.plm.types.api.DataType
import com.plm.types.api.Enumeration
import com.plm.types.api.EnumerationId
import com.plm.types.api.EnumerationValue
import com.plm.types.api.Labels
import com.plm.types.api.TypeId
import com.plm.types.api.UnitOfMeasure
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

@Repository
internal class AttributeRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()
    private val labelsType = object : TypeReference<Map<String, String>>() {}

    private fun ResultSet.labels(column: String): Labels = json.readValue(getString(column), labelsType)

    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private val definitionMapper =
        RowMapper { rs, _ ->
            AttributeDefinition(
                id = AttributeId(rs.getObject("id", UUID::class.java)),
                typeId = TypeId(rs.getObject("type_id", UUID::class.java)),
                name = rs.getString("name"),
                labels = rs.labels("labels"),
                description = rs.getString("description"),
                dataType = DataType.valueOf(rs.getString("data_type")),
                multiValued = rs.getBoolean("multi_valued"),
                required = rs.getBoolean("required"),
                immutable = rs.getBoolean("immutable"),
                minLength = rs.intOrNull("min_length"),
                maxLength = rs.intOrNull("max_length"),
                pattern = rs.getString("pattern"),
                minValue = rs.getBigDecimal("min_value"),
                maxValue = rs.getBigDecimal("max_value"),
                enumerationId = rs.getObject("enumeration_id", UUID::class.java)?.let(::EnumerationId),
                dimension = rs.getString("dimension"),
                displayUnit = rs.getString("display_unit"),
                defaultValue = rs.getString("default_value")?.let { json.readValue(it, Any::class.java) },
                sortOrder = rs.getInt("sort_order"),
                active = rs.getBoolean("active"),
                system = rs.getBoolean("system"),
            )
        }

    // ---------- Type hierarchy ----------

    /** [type] and its ancestors, root first. Empty if the type doesn't exist in [tenant]. */
    fun lineage(
        tenant: TenantId,
        type: TypeId,
    ): List<TypeId> =
        jdbc
            .sql(
                """
                with recursive up (id, parent_id, depth) as (
                    select id, parent_id, 0 from type_def where id = :type and tenant_id = :tenant
                    union all
                    select t.id, t.parent_id, up.depth + 1 from type_def t join up on t.id = up.parent_id
                )
                select id from up order by depth desc
                """,
            ).param("type", type.value)
            .param("tenant", tenant.value)
            .query(UUID::class.java)
            .list()
            .map { TypeId(requireNotNull(it)) }

    /** [type] and all its subtypes. */
    fun subtree(type: TypeId): List<TypeId> =
        jdbc
            .sql(
                """
                with recursive down (id) as (
                    select id from type_def where id = :type
                    union all
                    select t.id from type_def t join down on t.parent_id = down.id
                )
                select id from down
                """,
            ).param("type", type.value)
            .query(UUID::class.java)
            .list()
            .map { TypeId(requireNotNull(it)) }

    // ---------- Attribute definitions ----------

    fun definitionsOf(types: List<TypeId>): List<AttributeDefinition> {
        if (types.isEmpty()) return emptyList()
        return jdbc
            .sql("select * from attribute_def where type_id in (:types)")
            .param("types", types.map { it.value })
            .query(definitionMapper)
            .list()
    }

    fun findDefinition(
        tenant: TenantId,
        id: AttributeId,
    ): AttributeDefinition? =
        jdbc
            .sql("select * from attribute_def where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .query(definitionMapper)
            .optional()
            .orElse(null)

    fun insertDefinition(
        tenant: TenantId,
        def: AttributeDefinition,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into attribute_def
                        (id, tenant_id, type_id, name, labels, description, data_type, multi_valued, required,
                         immutable, min_length, max_length, pattern, min_value, max_value, enumeration_id, dimension,
                         display_unit, default_value, sort_order, active, system)
                    values (:id, :tenant, :type, :name, cast(:labels as jsonb), :description, :dataType, :multi,
                            :required, :immutable, :minLength, :maxLength, :pattern, :minValue, :maxValue,
                            :enumeration, :dimension, :displayUnit, cast(:defaultValue as jsonb), :sortOrder,
                            :active, false)
                    """,
                ).params(columns(def))
                .param("tenant", tenant.value)
                .param("type", def.typeId.value)
                .param("name", def.name)
                .param("dataType", def.dataType.name)
                .param("multi", def.multiValued)
                .param("enumeration", def.enumerationId?.value)
                .param("dimension", def.dimension)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_ATTRIBUTE_NAME",
                "This type already has an attribute named '${def.name}'",
                mapOf("name" to def.name),
            ).apply { initCause(e) }
        }
    }

    fun updateDefinition(def: AttributeDefinition) {
        jdbc
            .sql(
                """
                update attribute_def set
                    labels = cast(:labels as jsonb), description = :description, required = :required,
                    immutable = :immutable, min_length = :minLength, max_length = :maxLength, pattern = :pattern,
                    min_value = :minValue, max_value = :maxValue, display_unit = :displayUnit,
                    default_value = cast(:defaultValue as jsonb), sort_order = :sortOrder, active = :active
                where id = :id
                """,
            ).params(columns(def))
            .update()
    }

    private fun columns(def: AttributeDefinition): Map<String, Any?> =
        mapOf(
            "id" to def.id.value,
            "labels" to json.writeValueAsString(def.labels),
            "description" to def.description,
            "required" to def.required,
            "immutable" to def.immutable,
            "minLength" to def.minLength,
            "maxLength" to def.maxLength,
            "pattern" to def.pattern,
            "minValue" to def.minValue,
            "maxValue" to def.maxValue,
            "displayUnit" to def.displayUnit,
            "defaultValue" to def.defaultValue?.let { json.writeValueAsString(it) },
            "sortOrder" to def.sortOrder,
            "active" to def.active,
        )

    // ---------- Enumerations ----------

    fun enumerations(
        tenant: TenantId,
        ids: Collection<EnumerationId>? = null,
    ): List<Enumeration> {
        if (ids != null && ids.isEmpty()) return emptyList()
        val headers =
            jdbc
                .sql(
                    "select * from enumeration where tenant_id = :tenant" +
                        (if (ids != null) " and id in (:ids)" else "") + " order by name",
                ).param("tenant", tenant.value)
                .let { q -> if (ids != null) q.param("ids", ids.map { it.value }) else q }
                .query { rs, _ ->
                    Triple(
                        EnumerationId(rs.getObject("id", UUID::class.java)),
                        rs.getString("name"),
                        rs.labels("labels") to rs.getBoolean("system"),
                    )
                }.list()
        if (headers.isEmpty()) return emptyList()
        val values =
            jdbc
                .sql("select * from enumeration_value where enumeration_id in (:ids) order by sort_order, key")
                .param("ids", headers.map { it.first.value })
                .query { rs, _ ->
                    EnumerationId(rs.getObject("enumeration_id", UUID::class.java)) to
                        EnumerationValue(rs.getString("key"), rs.labels("labels"), rs.getBoolean("active"))
                }.list()
                .groupBy({ it.first }, { it.second })
        return headers.map { (id, name, rest) -> Enumeration(id, name, rest.first, rest.second, values[id].orEmpty()) }
    }

    fun insertEnumeration(
        tenant: TenantId,
        enumeration: Enumeration,
    ) {
        try {
            jdbc
                .sql(
                    "insert into enumeration (id, tenant_id, name, labels) values (:id, :tenant, :name, cast(:labels as jsonb))",
                ).param("id", enumeration.id.value)
                .param("tenant", tenant.value)
                .param("name", enumeration.name)
                .param("labels", json.writeValueAsString(enumeration.labels))
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_ENUMERATION_NAME",
                "An enumeration named '${enumeration.name}' already exists",
            ).apply { initCause(e) }
        }
        replaceValues(enumeration)
    }

    fun updateEnumeration(enumeration: Enumeration) {
        jdbc
            .sql("update enumeration set labels = cast(:labels as jsonb) where id = :id")
            .param("labels", json.writeValueAsString(enumeration.labels))
            .param("id", enumeration.id.value)
            .update()
        replaceValues(enumeration)
    }

    /** Upserts every value with its position. The service guarantees no existing key is dropped. */
    private fun replaceValues(enumeration: Enumeration) {
        enumeration.values.forEachIndexed { index, value ->
            jdbc
                .sql(
                    """
                    insert into enumeration_value (enumeration_id, key, labels, sort_order, active)
                    values (:id, :key, cast(:labels as jsonb), :sortOrder, :active)
                    on conflict (enumeration_id, key) do update
                        set labels = excluded.labels, sort_order = excluded.sort_order, active = excluded.active
                    """,
                ).param("id", enumeration.id.value)
                .param("key", value.key)
                .param("labels", json.writeValueAsString(value.labels))
                .param("sortOrder", index + 1)
                .param("active", value.active)
                .update()
        }
    }

    // ---------- Units ----------

    fun units(tenant: TenantId): List<UnitOfMeasure> =
        jdbc
            .sql("select * from unit_of_measure where tenant_id = :tenant order by dimension, factor, code")
            .param("tenant", tenant.value)
            .query { rs, _ ->
                UnitOfMeasure(
                    code = rs.getString("code"),
                    dimension = rs.getString("dimension"),
                    labels = rs.labels("labels"),
                    factor = rs.getBigDecimal("factor"),
                    offset = rs.getBigDecimal("offset"),
                )
            }.list()
}
