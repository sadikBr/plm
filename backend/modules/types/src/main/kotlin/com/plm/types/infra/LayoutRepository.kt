package com.plm.types.infra

import com.plm.platform.id.TenantId
import com.plm.types.api.LayoutDefinition
import com.plm.types.api.LayoutGroup
import com.plm.types.api.LayoutScreen
import com.plm.types.api.TypeId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class LayoutRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()
    private val groupsType = object : TypeReference<List<Map<String, Any?>>>() {}
    private val namesType = object : TypeReference<List<String>>() {}

    private val mapper =
        RowMapper { rs, _ ->
            LayoutDefinition(
                typeId = TypeId(rs.getObject("type_id", UUID::class.java)),
                screen = LayoutScreen.valueOf(rs.getString("screen")),
                groups = json.readValue(rs.getString("groups"), groupsType).map(::group),
                hidden = json.readValue(rs.getString("hidden"), namesType),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun group(raw: Map<String, Any?>) =
        LayoutGroup(
            key = raw["key"] as String,
            labels = raw["labels"] as Map<String, String>,
            columns = (raw["columns"] as Number).toInt(),
            attributes = raw["attributes"] as List<String>,
        )

    fun layouts(
        tenant: TenantId,
        types: List<TypeId>,
    ): List<LayoutDefinition> {
        if (types.isEmpty()) return emptyList()
        return jdbc
            .sql("select * from type_layout where tenant_id = :tenant and type_id in (:types)")
            .param("tenant", tenant.value)
            .param("types", types.map { it.value })
            .query(mapper)
            .list()
    }

    fun upsert(
        tenant: TenantId,
        layout: LayoutDefinition,
    ) {
        jdbc
            .sql(
                """
                insert into type_layout (type_id, screen, tenant_id, groups, hidden, updated_at)
                values (:type, :screen, :tenant, cast(:groups as jsonb), cast(:hidden as jsonb), :updatedAt)
                on conflict (type_id, screen) do update set
                    groups = excluded.groups, hidden = excluded.hidden, updated_at = excluded.updated_at
                """,
            ).param("type", layout.typeId.value)
            .param("screen", layout.screen.name)
            .param("tenant", tenant.value)
            .param("groups", json.writeValueAsString(layout.groups))
            .param("hidden", json.writeValueAsString(layout.hidden))
            .param("updatedAt", Timestamp.from(layout.updatedAt))
            .update()
    }

    /** @return whether a layout was removed. */
    fun delete(
        tenant: TenantId,
        type: TypeId,
        screen: LayoutScreen,
    ): Boolean =
        jdbc
            .sql("delete from type_layout where tenant_id = :tenant and type_id = :type and screen = :screen")
            .param("tenant", tenant.value)
            .param("type", type.value)
            .param("screen", screen.name)
            .update() > 0
}
