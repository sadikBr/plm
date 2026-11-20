package com.plm.extensions.infra

import com.plm.extensions.api.OperationRule
import com.plm.platform.error.ConflictException
import com.plm.platform.id.TenantId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class OperationRuleRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    private val mapper =
        RowMapper { rs, _ ->
            OperationRule(
                id = rs.getObject("id", UUID::class.java).toString(),
                name = rs.getString("name"),
                operations = json.readValue(rs.getString("operations"), Array<String>::class.java).toList(),
                types = json.readValue(rs.getString("types"), Array<String>::class.java).toList(),
                condition = rs.getString("condition"),
                message = rs.getString("message"),
                active = rs.getBoolean("active"),
                source = "site",
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                updatedBy = rs.getString("updated_by"),
            )
        }

    fun all(tenant: TenantId): List<OperationRule> =
        jdbc
            .sql("select * from operation_rule where tenant_id = :tenant order by name")
            .param("tenant", tenant.value)
            .query(mapper)
            .list()

    fun find(
        tenant: TenantId,
        id: UUID,
    ): OperationRule? =
        jdbc
            .sql("select * from operation_rule where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    fun save(
        tenant: TenantId,
        rule: OperationRule,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into operation_rule (id, tenant_id, name, operations, types, condition, message, active, updated_at, updated_by)
                    values (:id, :tenant, :name, cast(:operations as jsonb), cast(:types as jsonb), :condition, :message, :active, :at, :by)
                    on conflict (id) do update set
                        name = excluded.name, operations = excluded.operations, types = excluded.types,
                        condition = excluded.condition, message = excluded.message, active = excluded.active,
                        updated_at = excluded.updated_at, updated_by = excluded.updated_by
                    """.trimIndent(),
                ).param("id", UUID.fromString(rule.id))
                .param("tenant", tenant.value)
                .param("name", rule.name)
                .param("operations", json.writeValueAsString(rule.operations))
                .param("types", json.writeValueAsString(rule.types))
                .param("condition", rule.condition)
                .param("message", rule.message)
                .param("active", rule.active)
                .param("at", Timestamp.from(rule.updatedAt))
                .param("by", rule.updatedBy)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_RULE_NAME",
                "There's already a rule named '${rule.name}'",
                mapOf(
                    "name" to rule.name,
                ),
            )
        }
    }

    fun delete(
        tenant: TenantId,
        id: UUID,
    ) {
        jdbc
            .sql("delete from operation_rule where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .update()
    }
}
