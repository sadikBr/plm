package com.plm.extensions.infra

import com.plm.extensions.api.ActionConfiguration
import com.plm.extensions.api.ActionPlacement
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant

internal data class StoredConfiguration(
    val actionId: String,
    val configuration: ActionConfiguration,
    val updatedAt: Instant,
    val updatedBy: String,
)

@Repository
internal class ActionConfigRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    fun all(tenant: TenantId): List<StoredConfiguration> =
        jdbc
            .sql("select * from action_config where tenant_id = :tenant")
            .param("tenant", tenant.value)
            .query { rs, _ ->
                StoredConfiguration(
                    actionId = rs.getString("action_id"),
                    configuration =
                        ActionConfiguration(
                            hidden = rs.getBoolean("hidden"),
                            placement = rs.getString("placement")?.let(ActionPlacement::valueOf),
                            order = rs.getObject("sort_order") as Int?,
                            label = rs.getString("label"),
                            types =
                                rs
                                    .getString(
                                        "types",
                                    )?.let { json.readValue(it, Array<String>::class.java).toList() },
                            states =
                                rs
                                    .getString(
                                        "states",
                                    )?.let { json.readValue(it, Array<String>::class.java).toList() },
                            visibleWhen = rs.getString("visible_when"),
                        ),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                    updatedBy = rs.getString("updated_by"),
                )
            }.list()

    fun put(
        tenant: TenantId,
        actionId: String,
        configuration: ActionConfiguration,
        at: Instant,
        by: String,
    ) {
        jdbc
            .sql(
                """
                insert into action_config (tenant_id, action_id, hidden, placement, sort_order, label, types, states, visible_when, updated_at, updated_by)
                values (:tenant, :id, :hidden, :placement, :order, :label, cast(:types as jsonb), cast(:states as jsonb), :visibleWhen, :at, :by)
                on conflict (tenant_id, action_id) do update set
                    hidden = excluded.hidden, placement = excluded.placement, sort_order = excluded.sort_order,
                    label = excluded.label, types = excluded.types, states = excluded.states, visible_when = excluded.visible_when,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", actionId)
            .param("hidden", configuration.hidden)
            .param("placement", configuration.placement?.name)
            .param("order", configuration.order)
            .param("label", configuration.label)
            .param("types", configuration.types?.let(json::writeValueAsString))
            .param("states", configuration.states?.let(json::writeValueAsString))
            .param("visibleWhen", configuration.visibleWhen)
            .param("at", Timestamp.from(at))
            .param("by", by)
            .update()
    }

    fun delete(
        tenant: TenantId,
        actionId: String,
    ) {
        jdbc
            .sql("delete from action_config where tenant_id = :tenant and action_id = :id")
            .param("tenant", tenant.value)
            .param("id", actionId)
            .update()
    }
}
