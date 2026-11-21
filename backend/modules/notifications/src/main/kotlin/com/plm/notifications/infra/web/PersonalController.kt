package com.plm.notifications.infra.web

import com.plm.notifications.api.FavoriteKind
import com.plm.notifications.api.NotificationSettings
import com.plm.notifications.api.PersonalService
import com.plm.notifications.api.SubscriptionKind
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class TargetRequest(
    val label: String,
)

data class MarkReadRequest(
    /** None: all. */
    val ids: List<UUID> = emptyList(),
)

data class UnreadCount(
    val unread: Int,
)

@RestController
internal class PersonalController(
    private val personal: PersonalService,
) {
    @GetMapping("/api/v1/me/favorites")
    fun favorites(actor: Actor) = personal.favorites(actor)

    @PutMapping("/api/v1/me/favorites/{kind}/{targetId}")
    fun addFavorite(
        actor: Actor,
        @PathVariable kind: FavoriteKind,
        @PathVariable targetId: UUID,
        @RequestBody request: TargetRequest,
    ) = personal.addFavorite(actor, kind, targetId, request.label)

    @DeleteMapping("/api/v1/me/favorites/{kind}/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeFavorite(
        actor: Actor,
        @PathVariable kind: FavoriteKind,
        @PathVariable targetId: UUID,
    ) = personal.removeFavorite(actor, kind, targetId)

    @GetMapping("/api/v1/me/recent")
    fun recent(
        actor: Actor,
        @RequestParam(defaultValue = "15") limit: Int,
    ) = personal.recent(actor, limit)

    @PostMapping("/api/v1/me/recent/{objectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun visited(
        actor: Actor,
        @PathVariable objectId: UUID,
    ) = personal.visited(actor, objectId)

    @GetMapping("/api/v1/me/subscriptions")
    fun subscriptions(actor: Actor) = personal.subscriptions(actor)

    @PutMapping("/api/v1/me/subscriptions/{kind}/{targetId}")
    fun subscribe(
        actor: Actor,
        @PathVariable kind: SubscriptionKind,
        @PathVariable targetId: UUID,
        @RequestBody request: TargetRequest,
    ) = personal.subscribe(actor, kind, targetId, request.label)

    @DeleteMapping("/api/v1/me/subscriptions/{kind}/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unsubscribe(
        actor: Actor,
        @PathVariable kind: SubscriptionKind,
        @PathVariable targetId: UUID,
    ) = personal.unsubscribe(actor, kind, targetId)

    @GetMapping("/api/v1/me/notifications")
    fun notifications(
        actor: Actor,
        @RequestParam(defaultValue = "false") unread: Boolean,
        @RequestParam(defaultValue = "30") limit: Int,
    ) = personal.notifications(actor, unread, limit)

    @GetMapping("/api/v1/me/notifications/unread")
    fun unread(actor: Actor) = UnreadCount(personal.unreadCount(actor))

    @PostMapping("/api/v1/me/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markRead(
        actor: Actor,
        @RequestBody request: MarkReadRequest,
    ) = personal.markRead(actor, request.ids)

    @GetMapping("/api/v1/me/notification-settings")
    fun settings(actor: Actor) = personal.settings(actor)

    @PutMapping("/api/v1/me/notification-settings")
    fun saveSettings(
        actor: Actor,
        @RequestBody settings: NotificationSettings,
    ) = personal.saveSettings(actor, settings)
}
