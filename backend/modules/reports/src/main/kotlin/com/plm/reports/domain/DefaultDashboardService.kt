package com.plm.reports.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.reports.api.Dashboard
import com.plm.reports.api.DashboardService
import com.plm.reports.api.DashboardSettings
import com.plm.reports.api.Widget
import com.plm.reports.api.WidgetKind
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** What everyone's home page shows until they choose a dashboard. */
internal val DEFAULT_HOME =
    Dashboard(
        id = UUID(0, 0),
        name = "Home",
        owner = "system",
        shared = true,
        widgets =
            listOf(
                Widget("checked-out", WidgetKind.CHECKED_OUT, "Checked out by me", width = 1),
                Widget("recent", WidgetKind.RECENT, "Recently changed", width = 1),
            ),
        updatedAt = java.time.Instant.EPOCH,
    )

@Service
@Transactional
internal class DefaultDashboardService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : DashboardService {
    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<Dashboard> =
        jdbc
            .sql(
                "select * from dashboard where tenant_id = :tenant and (owner = :me or shared) order by owner = :me desc, lower(name)",
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .query { rs, _ ->
                Dashboard(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    owner = rs.getString("owner"),
                    shared = rs.getBoolean("shared"),
                    widgets = reportJson.readValue(rs.getString("widgets"), Array<Widget>::class.java).toList(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }.list()

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: UUID,
    ): Dashboard =
        list(actor).firstOrNull { it.id == id }
            ?: throw NotFoundException("DASHBOARD_NOT_FOUND", "There's no such dashboard")

    override fun create(
        actor: Actor,
        settings: DashboardSettings,
    ): Dashboard = save(actor, null, settings)

    override fun update(
        actor: Actor,
        id: UUID,
        settings: DashboardSettings,
    ): Dashboard = save(actor, id, settings)

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        own(actor, id)
        jdbc.sql("delete from dashboard where id = :id").param("id", id).update()
    }

    private fun own(
        actor: Actor,
        id: UUID,
    ) {
        if (get(actor, id).owner !=
            actor.principal.value
        ) {
            throw ForbiddenException("ACCESS_DENIED", "Only its owner can change it")
        }
    }

    private fun save(
        actor: Actor,
        id: UUID?,
        s: DashboardSettings,
    ): Dashboard {
        val name = s.name.trim()
        if (name.isEmpty() ||
            name.length > 100
        ) {
            throw ValidationException("INVALID_DASHBOARD", "Give the dashboard a name")
        }
        if (s.widgets.size > 24) throw ValidationException("INVALID_DASHBOARD", "At most 24 widgets")
        s.widgets.forEach { w ->
            if (w.kind.name.startsWith("REPORT") &&
                w.reportId == null
            ) {
                throw ValidationException("INVALID_DASHBOARD", "Choose a report for each report widget")
            }
        }
        if (id != null) own(actor, id)
        val newId = id ?: UuidV7.generate(clock)
        val now = Timestamp.from(clock.instant())
        jdbc
            .sql(
                """
                insert into dashboard (id, tenant_id, owner, name, shared, widgets, created_at, updated_at)
                values (:id, :tenant, :owner, :name, :shared, cast(:widgets as jsonb), :now, :now)
                on conflict (id) do update set name = excluded.name, shared = excluded.shared, widgets = excluded.widgets,
                    updated_at = excluded.updated_at
                """.trimIndent(),
            ).param("id", newId)
            .param("tenant", actor.tenant.value)
            .param("owner", actor.principal.value)
            .param("name", name)
            .param("shared", s.shared)
            .param(
                "widgets",
                reportJson.writeValueAsString(
                    s.widgets.map {
                        it.copy(width = it.width.coerceIn(1, 2), limit = it.limit.coerceIn(1, 50))
                    },
                ),
            ).param("now", now)
            .update()
        return get(actor, newId)
    }

    @Transactional(readOnly = true)
    override fun home(actor: Actor): Dashboard {
        val chosen =
            jdbc
                .sql("select dashboard_id from home_page where tenant_id = :tenant and principal_id = :me")
                .param("tenant", actor.tenant.value)
                .param("me", actor.principal.value)
                .query { rs, _ -> rs.getObject("dashboard_id", UUID::class.java) }
                .optional()
                .orElse(null)
        return chosen?.let { id -> list(actor).firstOrNull { it.id == id } } ?: DEFAULT_HOME
    }

    override fun setHome(
        actor: Actor,
        dashboardId: UUID?,
    ): Dashboard {
        jdbc
            .sql("delete from home_page where tenant_id = :tenant and principal_id = :me")
            .param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .update()
        if (dashboardId != null) {
            get(actor, dashboardId)
            jdbc
                .sql("insert into home_page (tenant_id, principal_id, dashboard_id) values (:tenant, :me, :id)")
                .param("tenant", actor.tenant.value)
                .param("me", actor.principal.value)
                .param("id", dashboardId)
                .update()
        }
        return home(actor)
    }
}
