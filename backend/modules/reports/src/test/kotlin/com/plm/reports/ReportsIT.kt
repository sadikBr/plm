package com.plm.reports

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.notifications.api.MailService
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.reports.api.AlertWhen
import com.plm.reports.api.DashboardService
import com.plm.reports.api.DashboardSettings
import com.plm.reports.api.ReportService
import com.plm.reports.api.ReportSettings
import com.plm.reports.api.Widget
import com.plm.reports.api.WidgetKind
import com.plm.reports.domain.ReportScheduler
import com.plm.search.api.SearchQuery
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.MutableClock
import plm.testing.PlmIntegrationTest
import java.time.Duration
import java.util.UUID

/** Reports, scheduled alerts, dashboards and home pages (roadmap 3.8). */
@PlmIntegrationTest
internal class ReportsIT(
    @Autowired private val reports: ReportService,
    @Autowired private val dashboards: DashboardService,
    @Autowired private val scheduler: ReportScheduler,
    @Autowired private val mail: MailService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val directory: PrincipalDirectory,
    @Autowired private val clock: MutableClock,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))
    private val word = "rp" + UUID.randomUUID().toString().take(6)

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Reports ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun part(
        context: ContextId,
        name: String,
        material: String,
    ) = versioning
        .create(
            alice,
            CreateVersionedObject(
                TypeId.PART,
                context,
                "$name $word",
                attributes =
                    mapOf(
                        "material" to material,
                    ),
            ),
        ).id

    /** Waits for the background indexer and job worker. */
    private fun <T> eventually(check: () -> T): T {
        var last: Throwable? = null
        repeat(100) {
            try {
                return check()
            } catch (e: AssertionError) {
                last = e
                Thread.sleep(150)
            }
        }
        throw last!!
    }

    @Test
    fun `a report finds what its search finds, with its columns and counts`() {
        val p = product()
        part(p, "Hub", "Titanium")
        part(p, "Axle", "Titanium")
        part(p, "Plate", "Steel")
        val settings =
            ReportSettings(
                "Titanium $word",
                query =
                    SearchQuery(
                        text = word,
                        attributes =
                            mapOf(
                                "material" to "titan",
                            ),
                    ),
                columns = listOf("number", "attr:material"),
                groupBy = "attr:material",
            )
        val report = reports.create(alice, settings)
        val result = eventually { reports.run(alice, report.id).also { assertThat(it.total).isEqualTo(2) } }
        assertThat(result.groups.single().key).isEqualTo("Titanium")
        assertThat(result.rows.map { it.attributes["material"] }).containsOnly("Titanium")
        // Others only see it once it's shared, and then with their own access.
        assertThat(runCatching { reports.get(bob, report.id) }.exceptionOrNull()).isInstanceOf(PlmException::class.java)
        reports.update(alice, report.id, settings.copy(shared = true))
        assertThat(reports.run(bob, report.id).total).isZero()
        assertThat(
            reports
                .preview(alice, settings.copy(groupBy = "state"))
                .groups
                .single()
                .key,
        ).isEqualTo("IN_WORK")
    }

    @Test
    fun `a scheduled alert mails only when new matches appear`() {
        directory.recordSignIn(SignIn(TenantId.DEFAULT, alice.principal, "alice", "Alice", "alice@example.com"))
        val p = product()
        part(p, "Gear", "Steel")
        eventually {
            assertThat(reports.preview(alice, ReportSettings("x", query = SearchQuery(text = word))).total).isEqualTo(1)
        }
        val report =
            reports.create(
                alice,
                ReportSettings(
                    "New gears $word",
                    query = SearchQuery(text = word),
                    cron = "0 7 * * *",
                    recipients = listOf("alice", "qa@example.com"),
                    alert = AlertWhen.NEW,
                ),
            )
        assertThat(report.nextRunAt).isAfter(clock.instant())
        assertThat(
            runCatching {
                reports.create(alice, ReportSettings("No one", cron = "0 7 * * *"))
            }.exceptionOrNull()?.message,
        ).contains("send it to")

        clock.advance(Duration.ofDays(1))
        scheduler.fireDue()
        eventually { assertThat(reports.runs(alice, report.id)).hasSize(1) }
        assertThat(reports.runs(alice, report.id).single().sentTo).isZero()

        part(p, "Pinion", "Steel")
        eventually {
            assertThat(reports.preview(alice, ReportSettings("x", query = SearchQuery(text = word))).total).isEqualTo(2)
        }
        clock.advance(Duration.ofDays(1))
        scheduler.fireDue()
        eventually { assertThat(reports.runs(alice, report.id)).hasSize(2) }
        val second = reports.runs(alice, report.id).first()
        assertThat(second.newCount).isEqualTo(1)
        assertThat(second.sentTo).isEqualTo(2)
        val sent = mail.recent(TenantId.DEFAULT).first { it.origin == "report:${report.id}" }
        assertThat(sent.subject).isEqualTo("New gears $word: 1 new since the last run, 2 in all")
        assertThat(sent.to).containsExactlyInAnyOrder("alice@example.com", "qa@example.com")
    }

    @Test
    fun `dashboards hold widgets, and anyone can make a visible one their home page`() {
        assertThat(
            dashboards.home(bob).widgets.map { it.kind },
        ).containsExactly(WidgetKind.CHECKED_OUT, WidgetKind.RECENT, WidgetKind.RECENT_VISITS, WidgetKind.FAVORITES)
        val report = reports.create(alice, ReportSettings("By state $word", shared = true))
        val d =
            dashboards.create(
                alice,
                DashboardSettings(
                    "Team $word",
                    listOf(
                        Widget("w1", WidgetKind.REPORT_CHART, reportId = report.id, width = 2),
                        Widget("w2", WidgetKind.CHECKED_OUT),
                    ),
                    shared = true,
                ),
            )
        assertThat(
            runCatching {
                dashboards.create(alice, DashboardSettings("Bad", listOf(Widget("w", WidgetKind.REPORT_TABLE))))
            }.exceptionOrNull()?.message,
        ).contains("Choose a report")
        assertThat(dashboards.setHome(bob, d.id).id).isEqualTo(d.id)
        assertThat(
            dashboards
                .home(bob)
                .widgets
                .first()
                .reportId,
        ).isEqualTo(report.id)
        assertThat(runCatching { dashboards.delete(bob, d.id) }.exceptionOrNull()?.message).contains("owner")
        dashboards.setHome(bob, null)
        assertThat(dashboards.home(bob).name).isEqualTo("Home")
    }
}
