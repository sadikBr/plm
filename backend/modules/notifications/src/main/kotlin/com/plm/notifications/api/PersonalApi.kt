package com.plm.notifications.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

enum class FavoriteKind { OBJECT, CONTEXT, FOLDER, REPORT }

enum class SubscriptionKind { OBJECT, FOLDER, CONTEXT }

enum class MailPreference {
    /** In the app only. */
    NONE,

    /** A mail for each notification. */
    IMMEDIATE,

    /** One mail a day with what's new. */
    DAILY,
}

data class Favorite(
    val kind: FavoriteKind,
    val targetId: UUID,
    val label: String,
    val createdAt: Instant,
)

data class RecentItem(
    val objectId: UUID,
    val number: String,
    val name: String,
    val version: String,
    val state: String,
    val typeId: UUID,
    val visitedAt: Instant,
)

data class Subscription(
    val kind: SubscriptionKind,
    val targetId: UUID,
    val label: String,
    val createdAt: Instant,
)

data class Notification(
    val id: UUID,
    val title: String,
    val body: String?,
    val link: String?,
    val objectId: UUID?,
    val actor: String?,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class NotificationSettings(
    val mail: MailPreference,
)

/**
 * Personal tracking (roadmap 3.9): what you keep at hand (favorites), what you looked at lately,
 * what you follow (subscriptions to objects, folders and contexts) and what happened there
 * (notifications, in the app and by mail, immediately or as a daily digest). Everything here is the
 * actor's own. Error code: `TARGET_NOT_FOUND`.
 */
interface PersonalService {
    fun favorites(actor: Actor): List<Favorite>

    fun addFavorite(
        actor: Actor,
        kind: FavoriteKind,
        targetId: UUID,
        label: String,
    ): Favorite

    fun removeFavorite(
        actor: Actor,
        kind: FavoriteKind,
        targetId: UUID,
    )

    fun visited(
        actor: Actor,
        objectId: UUID,
    )

    /** Objects looked at lately, still readable, newest first. */
    fun recent(
        actor: Actor,
        limit: Int = 15,
    ): List<RecentItem>

    fun subscriptions(actor: Actor): List<Subscription>

    fun subscribe(
        actor: Actor,
        kind: SubscriptionKind,
        targetId: UUID,
        label: String,
    ): Subscription

    fun unsubscribe(
        actor: Actor,
        kind: SubscriptionKind,
        targetId: UUID,
    )

    fun notifications(
        actor: Actor,
        unreadOnly: Boolean = false,
        limit: Int = 30,
    ): List<Notification>

    fun unreadCount(actor: Actor): Int

    /** Marks these (or, with none, all) as read. */
    fun markRead(
        actor: Actor,
        ids: List<UUID>,
    )

    fun settings(actor: Actor): NotificationSettings

    fun saveSettings(
        actor: Actor,
        settings: NotificationSettings,
    ): NotificationSettings

    /** For other modules: tells someone something (respecting their mail preference). */
    fun notify(
        tenant: com.plm.platform.id.TenantId,
        principal: String,
        title: String,
        body: String? = null,
        link: String? = null,
        objectId: UUID? = null,
        actor: String? = null,
    )
}
