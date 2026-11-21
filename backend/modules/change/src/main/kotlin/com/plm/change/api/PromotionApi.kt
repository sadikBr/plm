package com.plm.change.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

enum class PromotionStatus { UNDER_REVIEW, APPROVED, REJECTED, CANCELLED }

data class PromotionItem(
    val objectId: UUID,
    val number: String?,
    val name: String?,
    val typeId: UUID?,
    /** The version asked to be promoted. */
    val version: String,
    val stateBefore: String,
    /** Its latest state now (null when the reader can't see it). */
    val stateNow: String?,
)

data class PromotionRequest(
    val id: UUID,
    val number: String,
    val name: String,
    val targetState: String,
    val contextId: UUID,
    val status: PromotionStatus,
    val comment: String?,
    val instanceId: UUID?,
    val createdBy: String,
    val createdAt: Instant,
    val decidedAt: Instant?,
    val items: List<PromotionItem>,
)

data class CreatePromotion(
    val name: String,
    val targetState: String,
    val objectIds: List<UUID>,
    val comment: String? = null,
    /** The review process; the built-in "Promotion review" without. */
    val templateId: UUID? = null,
)

/**
 * Promotion requests (roadmap 5.7): anyone who can read objects may ask for them to be promoted;
 * the review process decides. Meanwhile the objects are locked (no check-out, state change,
 * revision, move or deletion); approval promotes them all to the target state.
 */
interface PromotionService {
    fun list(
        actor: Actor,
        status: PromotionStatus? = null,
    ): List<PromotionRequest>

    fun get(
        actor: Actor,
        id: UUID,
    ): PromotionRequest

    /** Promotion requests an object is (or was) in, newest first. */
    fun forObject(
        actor: Actor,
        objectId: UUID,
    ): List<PromotionRequest>

    /** Creates the request and starts its review. */
    fun create(
        actor: Actor,
        request: CreatePromotion,
    ): PromotionRequest

    /** Its creator or an administrator, while under review: the objects are unlocked, unchanged. */
    fun cancel(
        actor: Actor,
        id: UUID,
    ): PromotionRequest
}

data class PromotionDecided(
    val requestId: UUID,
    val number: String,
    val status: PromotionStatus,
    val objectIds: List<UUID>,
) : DomainEvent {
    override val eventType = "change.PromotionDecided.v1"
    override val aggregateType get() = "promotion-request"
    override val aggregateId get() = requestId
}
