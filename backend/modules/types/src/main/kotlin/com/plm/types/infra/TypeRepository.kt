package com.plm.types.infra

import com.plm.platform.error.ConflictException
import com.plm.platform.id.TenantId
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeDefinition
import com.plm.types.api.TypeId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
internal class TypeRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()
    private val labelsType = object : TypeReference<Map<String, String>>() {}

    private val mapper =
        RowMapper { rs, _ ->
            TypeDefinition(
                id = TypeId(rs.getObject("id", UUID::class.java)),
                parentId = rs.getObject("parent_id", UUID::class.java)?.let(::TypeId),
                name = rs.getString("name"),
                displayName = rs.getString("display_name"),
                baseClass = BaseClass.valueOf(rs.getString("base_class")),
                description = rs.getString("description"),
                labels = json.readValue(rs.getString("labels"), labelsType),
                icon = rs.getString("icon"),
                instantiable = rs.getBoolean("instantiable"),
            )
        }

    fun find(
        tenant: TenantId,
        id: TypeId,
    ): TypeDefinition? =
        jdbc
            .sql("select * from type_def where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .query(mapper)
            .optional()
            .orElse(null)

    fun list(tenant: TenantId): List<TypeDefinition> =
        jdbc
            .sql("select * from type_def where tenant_id = :tenant order by name")
            .param("tenant", tenant.value)
            .query(mapper)
            .list()

    fun update(type: TypeDefinition) {
        jdbc
            .sql(
                """
                update type_def set display_name = :displayName, description = :description,
                       labels = cast(:labels as jsonb), icon = :icon, instantiable = :instantiable
                where id = :id
                """,
            ).param("id", type.id.value)
            .param("displayName", type.displayName)
            .param("description", type.description)
            .param("labels", json.writeValueAsString(type.labels))
            .param("icon", type.icon)
            .param("instantiable", type.instantiable)
            .update()
    }

    fun insert(
        tenant: TenantId,
        type: TypeDefinition,
        now: Instant,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into type_def (id, tenant_id, parent_id, name, display_name, base_class, created_at)
                    values (:id, :tenant, :parent, :name, :displayName, :baseClass, :now)
                    """,
                ).param("id", type.id.value)
                .param("tenant", tenant.value)
                .param("parent", type.parentId?.value)
                .param("name", type.name)
                .param("displayName", type.displayName)
                .param("baseClass", type.baseClass.name)
                .param("now", Timestamp.from(now))
                .update()
            jdbc
                .sql(
                    """
                    insert into type_closure (ancestor_id, descendant_id, depth)
                    select ancestor_id, :id, depth + 1 from type_closure where descendant_id = :parent
                    union all
                    select :id, :id, 0
                    """,
                ).param("id", type.id.value)
                .param("parent", type.parentId?.value)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_TYPE_NAME",
                "A type named '${type.name}' already exists",
                mapOf("name" to type.name),
            ).apply { initCause(e) }
        }
    }
}
