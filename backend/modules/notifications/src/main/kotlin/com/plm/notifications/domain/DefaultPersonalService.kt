package com.plm.notifications.domain

import com.plm.identity.api.PrincipalDirectory
import com.plm.notifications.api.Favorite
import com.plm.notifications.api.FavoriteKind
import com.plm.notifications.api.Mail
import com.plm.notifications.api.MailPreference
import com.plm.notifications.api.MailService
import com.plm.notifications.api.Notification
import com.plm.notifications.api.NotificationSettings
import com.plm.notifications.api.PersonalService
import com.plm.notifications.api.RecentItem
import com.plm.notifications.api.Subscription
import com.plm.notifications.api.SubscriptionKind
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultPersonalService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val mail: MailService,
    private val directory: PrincipalDirectory,
    private val clock: Clock,
    @param:Value("\${plm.ui-url:http://localhost:5173}") private val uiUrl: String,
) : PersonalService {
    private fun now() = Timestamp.from(clock.instant())

    private fun label(s: String) =
        s.trim().take(200).ifEmpty {
            throw ValidationException("INVALID_LABEL", "A label is needed")
        }

    // ---------- Favorites ----------

    @Transactional(readOnly = true)
    override fun favorites(actor: Actor): List<Favorite> =
        jdbc
            .sql("select * from favorite where tenant_id = :tenant and principal_id = :me order by lower(label)")
            .param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .query { rs, _ ->
                Favorite(
                    FavoriteKind.valueOf(rs.getString("kind")),
                    rs.getObject("target_id", UUID::class.java),
                    rs.getString("label"),
                    rs.getTimestamp("created_at").toInstant(),
                )
            }.list()

    override fun addFavorite(
        actor: Actor,
        kind: FavoriteKind,
        targetId: UUID,
        label: String,
    ): Favorite {
        jdbc
            .sql(
                """
                insert into favorite (tenant_id, principal_id, kind, target_id, label, created_at) values (:tenant, :me, :kind, :target, :label, :now)
                on conflict (tenant_id, principal_id, kind, target_id) do update set label = excluded.label
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .param("kind", kind.name)
            .param("target", targetId)
            .param("label", label(label))
            .param("now", now())
            .update()
        return favorites(actor).first { it.kind == kind && it.targetId == targetId }
    }

    override fun removeFavorite(
        actor: Actor,
        kind: FavoriteKind,
        targetId: UUID,
    ) {
        jdbc
            .sql(
                "delete from favorite where tenant_id = :tenant and principal_id = :me and kind = :kind and target_id = :target",
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .param("kind", kind.name)
            .param("target", targetId)
            .update()
    }

    // ---------- Recent ----------

    override fun visited(
        actor: Actor,
        objectId: UUID,
    ) {
        jdbc
            .sql(
                """
                insert into recent_visit (tenant_id, principal_id, object_id, visited_at) values (:tenant, :me, :id, :now)
                on conflict (tenant_id, principal_id, object_id) do update set visited_at = excluded.visited_at
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .param("id", objectId)
            .param("now", now())
            .update()
        // Only the latest 50 are kept.
        jdbc
            .sql(
                """
                delete from recent_visit where tenant_id = :tenant and principal_id = :me and object_id not in (
                    select object_id from recent_visit where tenant_id = :tenant and principal_id = :me order by visited_at desc limit 50)
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .update()
    }

    @Transactional(readOnly = true)
    override fun recent(
        actor: Actor,
        limit: Int,
    ): List<RecentItem> {
        val visits =
            jdbc
                .sql(
                    "select object_id, visited_at from recent_visit where tenant_id = :tenant and principal_id = :me order by visited_at desc limit :limit",
                ).param("tenant", actor.tenant.value)
                .param("me", actor.principal.value)
                .param("limit", limit.coerceIn(1, 50))
                .query { rs, _ ->
                    rs.getObject("object_id", UUID::class.java) to
                        rs.getTimestamp("visited_at").toInstant()
                }.list()
        if (visits.isEmpty()) return emptyList()
        val objects =
            versioning
                .list(
                    actor,
                    ObjectQuery(
                        ids =
                            visits.map {
                                MasterId(it.first)
                            },
                        limit = 50,
                    ),
                ).associateBy { it.id.value }
        return visits.mapNotNull { (id, at) ->
            objects[id]?.let {
                RecentItem(
                    id,
                    it.number,
                    it.name,
                    it.latest.version,
                    it.latest.state,
                    it.typeId.value,
                    at,
                )
            }
        }
    }

    // ---------- Subscriptions ----------

    @Transactional(readOnly = true)
    override fun subscriptions(actor: Actor): List<Subscription> =
        jdbc
            .sql("select * from subscription where tenant_id = :tenant and principal_id = :me order by lower(label)")
            .param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .query { rs, _ ->
                Subscription(
                    SubscriptionKind.valueOf(rs.getString("kind")),
                    rs.getObject("target_id", UUID::class.java),
                    rs.getString("label"),
                    rs.getTimestamp("created_at").toInstant(),
                )
            }.list()

    override fun subscribe(
        actor: Actor,
        kind: SubscriptionKind,
        targetId: UUID,
        label: String,
    ): Subscription {
        if (kind == SubscriptionKind.OBJECT) versioning.get(actor, MasterId(targetId))
        jdbc
            .sql(
                """
                insert into subscription (tenant_id, principal_id, kind, target_id, label, site_admin, created_at)
                values (:tenant, :me, :kind, :target, :label, :admin, :now)
                on conflict (tenant_id, principal_id, kind, target_id) do update set label = excluded.label, site_admin = excluded.site_admin
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .param("kind", kind.name)
            .param("target", targetId)
            .param("label", label(label))
            .param("admin", actor.siteAdmin)
            .param("now", now())
            .update()
        return subscriptions(actor).first { it.kind == kind && it.targetId == targetId }
    }

    override fun unsubscribe(
        actor: Actor,
        kind: SubscriptionKind,
        targetId: UUID,
    ) {
        jdbc
            .sql(
                "delete from subscription where tenant_id = :tenant and principal_id = :me and kind = :kind and target_id = :target",
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .param("kind", kind.name)
            .param("target", targetId)
            .update()
    }

    // ---------- Notifications ----------

    @Transactional(readOnly = true)
    override fun notifications(
        actor: Actor,
        unreadOnly: Boolean,
        limit: Int,
    ): List<Notification> =
        jdbc
            .sql(
                """
                select * from notification where tenant_id = :tenant and principal_id = :me and (not :unread or read_at is null)
                order by seq desc limit :limit
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .param("unread", unreadOnly)
            .param("limit", limit.coerceIn(1, 200))
            .query { rs, _ ->
                Notification(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("title"),
                    rs.getString("body"),
                    rs.getString("link"),
                    rs.getObject("object_id", UUID::class.java),
                    rs.getString("actor"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("read_at")?.toInstant(),
                )
            }.list()

    @Transactional(readOnly = true)
    override fun unreadCount(actor: Actor): Int =
        jdbc
            .sql(
                "select count(*) from notification where tenant_id = :tenant and principal_id = :me and read_at is null",
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .query(Int::class.java)
            .single()

    override fun markRead(
        actor: Actor,
        ids: List<UUID>,
    ) {
        jdbc
            .sql(
                """
                update notification set read_at = :now where tenant_id = :tenant and principal_id = :me and read_at is null
                  and (cast(:ids as uuid[]) is null or id = any(cast(:ids as uuid[])))
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .param("ids", ids.takeIf { it.isNotEmpty() }?.toTypedArray())
            .param("now", now())
            .update()
    }

    @Transactional(readOnly = true)
    override fun settings(actor: Actor): NotificationSettings =
        NotificationSettings(preference(actor.tenant, actor.principal.value))

    fun preference(
        tenant: TenantId,
        principal: String,
    ): MailPreference =
        jdbc
            .sql("select mail from notification_setting where tenant_id = :tenant and principal_id = :me")
            .param("tenant", tenant.value)
            .param("me", principal)
            .query(String::class.java)
            .optional()
            .map { MailPreference.valueOf(it) }
            .orElse(MailPreference.DAILY)

    override fun saveSettings(
        actor: Actor,
        settings: NotificationSettings,
    ): NotificationSettings {
        jdbc
            .sql(
                """
                insert into notification_setting (tenant_id, principal_id, mail) values (:tenant, :me, :mail)
                on conflict (tenant_id, principal_id) do update set mail = excluded.mail
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .param("mail", settings.mail.name)
            .update()
        return settings(actor)
    }

    override fun notify(
        tenant: TenantId,
        principal: String,
        title: String,
        body: String?,
        link: String?,
        objectId: UUID?,
        actor: String?,
    ) {
        val immediate = preference(tenant, principal) == MailPreference.IMMEDIATE
        jdbc
            .sql(
                """
                insert into notification (id, tenant_id, principal_id, title, body, link, object_id, actor, created_at, mailed_at)
                values (:id, :tenant, :me, :title, :body, :link, :object, :actor, :now, :mailed)
                """.trimIndent(),
            ).param("id", UuidV7.generate(clock))
            .param("tenant", tenant.value)
            .param("me", principal)
            .param("title", title.take(300))
            .param("body", body?.take(2000))
            .param("link", link)
            .param("object", objectId)
            .param("actor", actor)
            .param("now", now())
            .param("mailed", if (immediate) now() else null)
            .update()
        if (immediate) {
            val to = mail.addresses(tenant, listOf(PrincipalId(principal)))
            if (to.isNotEmpty()) {
                val url = link?.let { uiUrl.trimEnd('/') + it }
                mail.queue(
                    tenant,
                    Mail(
                        to,
                        title,
                        listOfNotNull(body, url).joinToString("\n\n").ifEmpty {
                            title
                        },
                        origin = "notification",
                    ),
                )
            }
        }
    }

    /** Display name for messages. */
    fun nameOf(
        tenant: TenantId,
        principal: String?,
    ): String =
        principal?.let { if (it == "system") "The system" else directory.displayName(tenant, PrincipalId(it)) }
            ?: "Someone"
}
