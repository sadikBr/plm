package com.plm.types.infra

import com.plm.platform.error.ConflictException
import com.plm.platform.id.TenantId
import com.plm.types.api.NumberingScheme
import com.plm.types.api.NumberingSchemeId
import com.plm.types.api.RevisionScheme
import com.plm.types.api.TypeId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

/** One type's own overrides (nulls inherit). */
internal data class TypeRuleRow(
    val typeId: TypeId,
    val numberingSchemeId: NumberingSchemeId?,
    val revisionScheme: RevisionScheme?,
    val allowManualNumber: Boolean?,
)

@Repository
internal class TypeRuleRepository(
    private val jdbc: JdbcClient,
) {
    private val schemeMapper =
        RowMapper { rs, _ ->
            NumberingScheme(
                id = NumberingSchemeId(rs.getObject("id", UUID::class.java)),
                name = rs.getString("name"),
                prefix = rs.getString("prefix"),
                padding = rs.getInt("padding"),
                suffix = rs.getString("suffix"),
                nextValue = rs.getLong("next_value"),
            )
        }

    fun rules(types: List<TypeId>): List<TypeRuleRow> {
        if (types.isEmpty()) return emptyList()
        return jdbc
            .sql("select * from type_rule where type_id in (:types)")
            .param("types", types.map { it.value })
            .query { rs, _ ->
                TypeRuleRow(
                    typeId = TypeId(rs.getObject("type_id", UUID::class.java)),
                    numberingSchemeId = rs.getObject("numbering_scheme_id", UUID::class.java)?.let(::NumberingSchemeId),
                    revisionScheme = rs.getString("revision_scheme")?.let(RevisionScheme::valueOf),
                    allowManualNumber = rs.getObject("allow_manual_number") as Boolean?,
                )
            }.list()
    }

    fun upsert(
        tenant: TenantId,
        row: TypeRuleRow,
    ) {
        jdbc
            .sql(
                """
                insert into type_rule (type_id, tenant_id, numbering_scheme_id, revision_scheme, allow_manual_number)
                values (:type, :tenant, :scheme, :revision, :manual)
                on conflict (type_id) do update set
                    numbering_scheme_id = excluded.numbering_scheme_id,
                    revision_scheme = excluded.revision_scheme,
                    allow_manual_number = excluded.allow_manual_number
                """,
            ).param("type", row.typeId.value)
            .param("tenant", tenant.value)
            .param("scheme", row.numberingSchemeId?.value)
            .param("revision", row.revisionScheme?.name)
            .param("manual", row.allowManualNumber)
            .update()
    }

    fun schemes(tenant: TenantId): List<NumberingScheme> =
        jdbc
            .sql("select * from numbering_scheme where tenant_id = :tenant order by name")
            .param("tenant", tenant.value)
            .query(schemeMapper)
            .list()

    fun scheme(
        tenant: TenantId,
        id: NumberingSchemeId,
    ): NumberingScheme? =
        jdbc
            .sql("select * from numbering_scheme where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id.value)
            .query(schemeMapper)
            .optional()
            .orElse(null)

    fun insertScheme(
        tenant: TenantId,
        scheme: NumberingScheme,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into numbering_scheme (id, tenant_id, name, prefix, padding, suffix, next_value)
                    values (:id, :tenant, :name, :prefix, :padding, :suffix, :next)
                    """,
                ).param("id", scheme.id.value)
                .param("tenant", tenant.value)
                .param("name", scheme.name)
                .param("prefix", scheme.prefix)
                .param("padding", scheme.padding)
                .param("suffix", scheme.suffix)
                .param("next", scheme.nextValue)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(
                "DUPLICATE_NUMBERING_SCHEME",
                "A numbering scheme named '${scheme.name}' already exists",
            ).apply { initCause(e) }
        }
    }

    /** Atomically takes the counter's current value and advances it. */
    fun takeNext(
        tenant: TenantId,
        id: NumberingSchemeId,
    ): Long? =
        jdbc
            .sql(
                """
                update numbering_scheme set next_value = next_value + 1
                where tenant_id = :tenant and id = :id
                returning next_value - 1
                """,
            ).param("tenant", tenant.value)
            .param("id", id.value)
            .query(Long::class.java)
            .optional()
            .orElse(null)
}
