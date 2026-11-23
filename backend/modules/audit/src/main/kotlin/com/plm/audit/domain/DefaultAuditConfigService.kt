package com.plm.audit.domain

import com.plm.audit.api.AuditConfig
import com.plm.audit.api.AuditConfigChanged
import com.plm.audit.api.AuditConfigService
import com.plm.audit.api.AuditEventKind
import com.plm.audit.api.AuditPurged
import com.plm.audit.api.SaveAuditConfig
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Kinds that stay audited whatever the configuration says. */
internal val ALWAYS_AUDITED = listOf("access.", "identity.", "audit.")

private val KIND = Regex("^[a-z][a-zA-Z0-9]*\\.([A-Za-z0-9]+(\\.v\\d+)?)?$")

@Service
internal class DefaultAuditConfigService(
    private val jdbc: JdbcClient,
    private val events: DomainEvents,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : AuditConfigService {
    private val json = JsonMapper.builder().build()
    private val listType = object : TypeReference<List<String>>() {}

    private data class Cached(
        val excluded: List<String>,
        val at: Instant,
    )

    // Read on every event: cached briefly per site (other instances catch up within a minute).
    private val cache = ConcurrentHashMap<UUID, Cached>()

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators configure auditing")
    }

    private fun row(tenant: UUID) =
        jdbc
            .sql("select excluded::text, retention_days, updated_at, updated_by from audit_config where tenant_id = :t")
            .param("t", tenant)
            .query { rs, _ ->
                AuditConfig(
                    json.readValue(rs.getString(1), listType),
                    rs.getObject(2) as Int?,
                    ALWAYS_AUDITED,
                    rs.getTimestamp(3).toInstant(),
                    rs.getString(4),
                )
            }.optional()
            .orElse(null)

    /** Whether an event of [type] is audited at [tenant]. */
    fun audited(
        tenant: UUID,
        type: String,
    ): Boolean {
        if (ALWAYS_AUDITED.any { type.startsWith(it) }) return true
        val now = clock.instant()
        val c =
            cache[tenant]?.takeIf { Duration.between(it.at, now) < Duration.ofMinutes(1) }
                ?: Cached(row(tenant)?.excluded ?: emptyList(), now).also { cache[tenant] = it }
        return c.excluded.none { type.startsWith(it) }
    }

    @Transactional(readOnly = true)
    override fun config(actor: Actor): AuditConfig {
        requireAdmin(actor)
        return row(actor.tenant.value) ?: AuditConfig(emptyList(), null, ALWAYS_AUDITED, null, null)
    }

    @Transactional
    override fun update(
        actor: Actor,
        command: SaveAuditConfig,
    ): AuditConfig {
        requireAdmin(actor)
        val excluded =
            command.excluded
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val errors = mutableListOf<String>()
        excluded.filterNot { KIND.matches(it) }.forEach {
            errors +=
                "'$it' isn't an event kind, e.g. jobs. or jobs.JobFinished"
        }
        excluded.filter { e -> ALWAYS_AUDITED.any { e.startsWith(it) || it.startsWith(e) } }.forEach {
            errors +=
                "$it is always audited"
        }
        if (command.retentionDays != null && command.retentionDays < 30) errors += "Entries are kept at least 30 days"
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_AUDIT_CONFIG",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
        jdbc
            .sql(
                """
                insert into audit_config (tenant_id, excluded, retention_days, updated_at, updated_by)
                values (:t, cast(:e as jsonb), :r, :at, :by)
                on conflict (tenant_id) do update set excluded = excluded.excluded, retention_days = excluded.retention_days,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """,
            ).param("t", actor.tenant.value)
            .param("e", json.writeValueAsString(excluded))
            .param("r", command.retentionDays)
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        cache.remove(actor.tenant.value)
        events.publish(actor, AuditConfigChanged(actor.tenant.value, excluded, command.retentionDays))
        return config(actor)
    }

    @Transactional(readOnly = true)
    override fun kinds(actor: Actor): List<AuditEventKind> {
        requireAdmin(actor)
        val excluded = row(actor.tenant.value)?.excluded ?: emptyList()
        val counts =
            jdbc
                .sql(
                    "select split_part(action, '.', 1) || '.' as kind, count(*) from audit_log where tenant_id = :t group by 1 order by 2 desc",
                ).param("t", actor.tenant.value)
                .query { rs, _ -> rs.getString(1) to rs.getLong(2) }
                .list()
                .toMap()
        val kinds = (counts.keys + excluded).distinct().sorted()
        return kinds.map { k ->
            AuditEventKind(k, counts[k] ?: 0, excluded.contains(k), ALWAYS_AUDITED.any { k.startsWith(it) })
        }
    }

    override fun purge(actor: Actor): Long {
        requireAdmin(actor)
        return purgeTenant(actor.tenant.value, actor)
    }

    private fun purgeTenant(
        tenant: UUID,
        actor: Actor,
    ): Long =
        tx.execute {
            val days = row(tenant)?.retentionDays ?: return@execute 0L
            val before = clock.instant().minus(Duration.ofDays(days.toLong()))
            jdbc
                .sql(
                    "select set_config('plm.audit_purge_before', :b, true)",
                ).param("b", before.toString())
                .query()
                .singleRow()
            val n =
                jdbc
                    .sql(
                        "delete from audit_log where tenant_id = :t and occurred_at < :b",
                    ).param("t", tenant)
                    .param("b", Timestamp.from(before))
                    .update()
                    .toLong()
            jdbc.sql("select set_config('plm.audit_purge_before', '', true)").query().singleRow()
            if (n > 0) events.publish(actor, AuditPurged(tenant, before.toString(), n))
            n
        }

    /** Daily: each site's entries past its retention period. */
    @Scheduled(cron = "\${plm.audit.purge-cron:0 17 3 * * *}")
    fun purgeDaily() {
        val tenants =
            jdbc
                .sql("select tenant_id from audit_config where retention_days is not null")
                .query {
                    rs,
                    _,
                    ->
                    rs.getObject(1, UUID::class.java)
                }.list()
        tenants.forEach { purgeTenant(it, Actor(TenantId(it), PrincipalId("system"), siteAdmin = true)) }
    }
}
