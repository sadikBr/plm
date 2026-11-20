package com.plm.documents.infra

import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class TemplateRow(
    val id: UUID,
    val masterId: UUID,
    val name: String,
    val description: String?,
    val organizationId: UUID?,
    val active: Boolean,
    val updatedAt: Instant,
    val updatedBy: String,
)

@Repository
internal class DocumentTemplateRepository(
    private val jdbc: JdbcClient,
) {
    fun all(tenant: TenantId): List<TemplateRow> =
        jdbc
            .sql("select * from document_template where tenant_id = :tenant order by lower(name)")
            .param("tenant", tenant.value)
            .query { rs, _ ->
                TemplateRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("master_id", UUID::class.java),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getBoolean("active"),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("updated_by"),
                )
            }.list()

    fun find(
        tenant: TenantId,
        id: UUID,
    ) = all(tenant).firstOrNull { it.id == id }

    fun conflict(
        tenant: TenantId,
        name: String,
        masterId: UUID,
        except: UUID?,
    ): String? =
        jdbc
            .sql(
                """
                select case when lower(name) = lower(:name) then 'name' else 'object' end from document_template
                where tenant_id = :tenant and id <> :except and (lower(name) = lower(:name) or master_id = :master)
                limit 1
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("name", name)
            .param("master", masterId)
            .param("except", except ?: UUID(0, 0))
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun save(
        tenant: TenantId,
        t: TemplateRow,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into document_template (tenant_id, id, master_id, name, description, organization_id, active,
                                               created_at, created_by, updated_at, updated_by)
                values (:tenant, :id, :master, :name, :description, :org, :active, :now, :by, :now, :by)
                on conflict (id) do update set master_id = excluded.master_id, name = excluded.name,
                    description = excluded.description, organization_id = excluded.organization_id,
                    active = excluded.active, updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", t.id)
            .param("master", t.masterId)
            .param("name", t.name)
            .param("description", t.description)
            .param("org", t.organizationId)
            .param("active", t.active)
            .param("now", Timestamp.from(now))
            .param("by", t.updatedBy)
            .update()
    }

    fun delete(
        tenant: TenantId,
        id: UUID,
    ) {
        jdbc
            .sql(
                "delete from document_template where tenant_id = :tenant and id = :id",
            ).param("tenant", tenant.value)
            .param("id", id)
            .update()
    }
}
