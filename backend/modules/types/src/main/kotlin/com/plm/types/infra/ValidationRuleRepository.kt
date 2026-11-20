package com.plm.types.infra

import com.plm.platform.error.ConflictException
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.types.api.ValidationRule
import com.plm.types.api.ValidationRuleId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class ValidationRuleRepository(
    private val jdbc: JdbcClient,
) {
    private val mapper =
        RowMapper { rs, _ ->
            ValidationRule(
                id = ValidationRuleId(rs.getObject("id", UUID::class.java)),
                typeId = TypeId(rs.getObject("type_id", UUID::class.java)),
                name = rs.getString("name"),
                condition = rs.getString("condition"),
                message = rs.getString("message"),
                attribute = rs.getString("attribute"),
                active = rs.getBoolean("active"),
                order = rs.getInt("sort_order"),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                updatedBy = rs.getString("updated_by"),
            )
        }

    fun forTypes(
        tenant: TenantId,
        types: List<TypeId>,
    ): List<ValidationRule> {
        if (types.isEmpty()) return emptyList()
        return jdbc
            .sql(
                "select * from type_validation_rule where tenant_id = :tenant and type_id in (:types) order by sort_order, name",
            ).param("tenant", tenant.value)
            .param("types", types.map { it.value })
            .query(mapper)
            .list()
    }

    fun find(
        tenant: TenantId,
        id: ValidationRuleId,
    ): ValidationRule? =
        jdbc
            .sql("select * from type_validation_rule where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .query(mapper)
            .optional()
            .orElse(null)

    fun save(
        tenant: TenantId,
        rule: ValidationRule,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into type_validation_rule
                        (id, tenant_id, type_id, name, condition, message, attribute, active, sort_order,
                         created_at, created_by, updated_at, updated_by)
                    values (:id, :tenant, :type, :name, :condition, :message, :attribute, :active, :order, :at, :by, :at, :by)
                    on conflict (id) do update set
                        name = excluded.name, condition = excluded.condition, message = excluded.message,
                        attribute = excluded.attribute, active = excluded.active, sort_order = excluded.sort_order,
                        updated_at = excluded.updated_at, updated_by = excluded.updated_by
                    """.trimIndent(),
                ).param("id", rule.id.value)
                .param("tenant", tenant.value)
                .param("type", rule.typeId.value)
                .param("name", rule.name)
                .param("condition", rule.condition)
                .param("message", rule.message)
                .param("attribute", rule.attribute)
                .param("active", rule.active)
                .param("order", rule.order)
                .param("at", Timestamp.from(rule.updatedAt))
                .param("by", rule.updatedBy)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_RULE_NAME",
                "This type already has a rule named '${rule.name}'",
                mapOf(
                    "name" to rule.name,
                ),
            )
        }
    }

    fun delete(
        tenant: TenantId,
        id: ValidationRuleId,
    ) {
        jdbc
            .sql("delete from type_validation_rule where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .update()
    }
}
