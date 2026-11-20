package com.plm.extensions.infra.web

import com.plm.extensions.api.EventSubscriptionService
import com.plm.extensions.api.EventSubscriptionSettings
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

/** The events subscribers usually want, with what they mean. Any published type can be subscribed to. */
private val EVENT_TYPES =
    linkedMapOf(
        "versioning.ObjectCreated.v1" to "An object was created",
        "versioning.ObjectCheckedOut.v1" to "An object was checked out",
        "versioning.ObjectCheckedIn.v1" to "A new iteration was checked in",
        "versioning.CheckOutUndone.v1" to "A check-out was undone",
        "versioning.ObjectRevised.v1" to "A new revision was started",
        "versioning.ObjectStateChanged.v1" to "An object moved to another lifecycle state (from, to)",
        "versioning.ObjectRenamed.v1" to "An object's number or name changed",
        "versioning.ObjectLifecycleChanged.v1" to "An object moved to another lifecycle",
        "access.ContextCreated.v1" to "A product, library, project or organization was created",
        "access.TeamMemberPut.v1" to "Someone joined a team or changed role",
        "access.TeamMemberRemoved.v1" to "Someone left a team",
        "extensions.ExtensionActionInvoked.v1" to "An extension's action was run on an object",
        "types.TypeCreated.v1" to "A type was created",
    )

@RestController
internal class WebhookController(
    private val subscriptions: EventSubscriptionService,
) {
    /** Event types to offer when subscribing. */
    @GetMapping("/api/v1/event-types")
    fun eventTypes() = EVENT_TYPES.map { (type, description) -> EventTypeResponse(type, description) }

    @GetMapping("/api/v1/event-subscriptions")
    fun list(actor: Actor) = subscriptions.list(actor)

    /** Delivered, pending and dead deliveries per subscription. */
    @GetMapping("/api/v1/event-subscriptions/health")
    fun health(actor: Actor) = subscriptions.health(actor)

    @PostMapping("/api/v1/event-subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    fun define(
        actor: Actor,
        @RequestBody request: EventSubscriptionSettings,
    ) = subscriptions.define(actor, request)

    /** A secret left out keeps the current one. */
    @PutMapping("/api/v1/event-subscriptions/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: EventSubscriptionSettings,
    ) = subscriptions.update(actor, id, request)

    @DeleteMapping("/api/v1/event-subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = subscriptions.delete(actor, id)

    /** The latest deliveries, newest first. `status`: PENDING, DELIVERED or DEAD. */
    @GetMapping("/api/v1/webhook-deliveries")
    fun deliveries(
        actor: Actor,
        @RequestParam(required = false) subscriptionId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ) = subscriptions.deliveries(actor, subscriptionId, status, limit)

    /** Sends a delivery again from scratch, e.g. a dead one once the receiver is fixed. */
    @PostMapping("/api/v1/webhook-deliveries/{id}/redeliver")
    fun redeliver(
        actor: Actor,
        @PathVariable id: UUID,
    ) = subscriptions.redeliver(actor, id)
}

internal data class EventTypeResponse(
    val type: String,
    val description: String,
)
