package com.plm.extensions.infra

import com.plm.extensions.api.ExtensionManifest
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.Timestamp
import java.time.Instant

internal data class ExtensionRow(
    val manifest: ExtensionManifest,
    val source: String,
    val config: Map<String, Any?>,
    val secrets: Map<String, String>,
    val enabled: Boolean,
    val installedAt: Instant,
    val installedBy: String,
    val updatedAt: Instant,
    val updatedBy: String,
)

@Repository
internal class ExtensionRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Suppress("UNCHECKED_CAST")
    private val mapper =
        RowMapper { rs, _ ->
            ExtensionRow(
                manifest = json.readValue(rs.getString("manifest"), ExtensionManifest::class.java),
                source = rs.getString("source"),
                config = json.readValue(rs.getString("config"), Map::class.java) as Map<String, Any?>,
                secrets = json.readValue(rs.getString("secrets"), Map::class.java) as Map<String, String>,
                enabled = rs.getBoolean("enabled"),
                installedAt = rs.getTimestamp("installed_at").toInstant(),
                installedBy = rs.getString("installed_by"),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                updatedBy = rs.getString("updated_by"),
            )
        }

    fun all(tenant: TenantId): List<ExtensionRow> =
        jdbc
            .sql("select * from extension where tenant_id = :tenant order by name, id")
            .param("tenant", tenant.value)
            .query(mapper)
            .list()

    fun find(
        tenant: TenantId,
        id: String,
    ): ExtensionRow? =
        jdbc
            .sql("select * from extension where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    fun save(
        tenant: TenantId,
        row: ExtensionRow,
    ) {
        jdbc
            .sql(
                """
                insert into extension (tenant_id, id, name, version, manifest, source, config, secrets, enabled,
                                       installed_at, installed_by, updated_at, updated_by)
                values (:tenant, :id, :name, :version, cast(:manifest as jsonb), :source, cast(:config as jsonb),
                        cast(:secrets as jsonb), :enabled, :installedAt, :installedBy, :updatedAt, :updatedBy)
                on conflict (tenant_id, id) do update set
                    name = excluded.name, version = excluded.version, manifest = excluded.manifest, source = excluded.source,
                    config = excluded.config, secrets = excluded.secrets, enabled = excluded.enabled,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", row.manifest.id)
            .param("name", row.manifest.name)
            .param("version", row.manifest.version)
            .param("manifest", json.writeValueAsString(row.manifest))
            .param("source", row.source)
            .param("config", json.writeValueAsString(row.config))
            .param("secrets", json.writeValueAsString(row.secrets))
            .param("enabled", row.enabled)
            .param("installedAt", Timestamp.from(row.installedAt))
            .param("installedBy", row.installedBy)
            .param("updatedAt", Timestamp.from(row.updatedAt))
            .param("updatedBy", row.updatedBy)
            .update()
    }

    fun delete(
        tenant: TenantId,
        id: String,
    ) {
        jdbc
            .sql("delete from extension where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .update()
    }
}
