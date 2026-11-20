package com.plm.preferences.infra

import com.plm.access.api.ContextId
import com.plm.platform.id.TenantId
import com.plm.preferences.api.PreferenceValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.util.UUID

internal data class StoredValue(
    val scopeId: String,
    val value: PreferenceValue,
)

@Repository
internal class PreferenceRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    /** The context and its ancestors, root (the site) first. Reads the access module's closure table. */
    fun chain(context: ContextId): List<ContextId> =
        jdbc
            .sql("select ancestor_id from context_closure where descendant_id = :context order by depth desc")
            .param("context", context.value)
            .query { rs, _ -> ContextId(rs.getObject("ancestor_id", UUID::class.java)) }
            .list()

    fun site(tenant: TenantId): ContextId =
        jdbc
            .sql("select id from context where tenant_id = :tenant and parent_id is null")
            .param("tenant", tenant.value)
            .query { rs, _ -> ContextId(rs.getObject("id", UUID::class.java)) }
            .single()

    fun values(
        tenant: TenantId,
        scope: String,
        scopeIds: List<String>,
    ): List<StoredValue> {
        if (scopeIds.isEmpty()) return emptyList()
        return jdbc
            .sql("select * from preference_value where tenant_id = :tenant and scope = :scope and scope_id in (:ids)")
            .param("tenant", tenant.value)
            .param("scope", scope)
            .param("ids", scopeIds)
            .query { rs, _ ->
                StoredValue(
                    rs.getString("scope_id"),
                    PreferenceValue(
                        key = rs.getString("key"),
                        value = json.readValue(rs.getString("value"), Any::class.java),
                        locked = rs.getBoolean("locked"),
                        setAt = rs.getTimestamp("set_at").toInstant(),
                        setBy = rs.getString("set_by"),
                    ),
                )
            }.list()
    }

    fun put(
        tenant: TenantId,
        scope: String,
        scopeId: String,
        value: PreferenceValue,
    ) {
        jdbc
            .sql(
                """
                insert into preference_value (tenant_id, key, scope, scope_id, value, locked, set_at, set_by)
                values (:tenant, :key, :scope, :scopeId, cast(:value as jsonb), :locked, :setAt, :setBy)
                on conflict (tenant_id, key, scope, scope_id) do update
                    set value = excluded.value, locked = excluded.locked, set_at = excluded.set_at, set_by = excluded.set_by
                """,
            ).param("tenant", tenant.value)
            .param("key", value.key)
            .param("scope", scope)
            .param("scopeId", scopeId)
            .param("value", json.writeValueAsString(value.value))
            .param("locked", value.locked)
            .param("setAt", Timestamp.from(value.setAt))
            .param("setBy", value.setBy)
            .update()
    }

    fun delete(
        tenant: TenantId,
        key: String,
        scope: String,
        scopeId: String,
    ): Int =
        jdbc
            .sql(
                "delete from preference_value where tenant_id = :tenant and key = :key and scope = :scope and scope_id = :scopeId",
            ).param("tenant", tenant.value)
            .param("key", key)
            .param("scope", scope)
            .param("scopeId", scopeId)
            .update()
}
