package com.plm.integration.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** An attribute the plant needs that the object doesn't carry (procurement type, MRP controller...). */
data class PlantAttribute(
    val name: String,
    val label: String,
    val required: Boolean = false,
    val defaultValue: String? = null,
)

data class DistributionRule(
    val id: UUID,
    val typeId: UUID,
    /** Null: any context. */
    val contextId: UUID?,
    val state: String,
)

data class DistributionTarget(
    val id: UUID,
    val name: String,
    /** The ERP system, e.g. SAP S/4HANA, Oracle, Odoo. */
    val system: String,
    val plant: String,
    /** Where messages are POSTed; null when the ERP fetches them. */
    val endpoint: String?,
    val attributes: List<PlantAttribute>,
    val rules: List<DistributionRule>,
    val active: Boolean,
    val pending: Int,
    val failed: Int,
    val createdAt: Instant,
)

data class SaveTarget(
    val name: String,
    val system: String,
    val plant: String,
    val endpoint: String? = null,
    val attributes: List<PlantAttribute> = emptyList(),
    val active: Boolean = true,
)

data class SaveDistributionRule(
    val typeId: UUID,
    val contextId: UUID? = null,
    val state: String = "RELEASED",
)

/** A target's secret, shown once: messages are signed with it, and acknowledgements prove it. */
data class TargetSecret(
    val targetId: UUID,
    val secret: String,
)

/** Where an object stands at one target. */
data class ObjectDistribution(
    val targetId: UUID,
    val targetName: String,
    val system: String,
    val plant: String,
    /** NOT_SENT, PENDING, SENT, ACKNOWLEDGED, ERROR. */
    val status: String,
    val version: String?,
    /** The ERP's own key for it, from its acknowledgement. */
    val erpKey: String?,
    val message: String?,
    val plantValues: Map<String, String>,
    val attributes: List<PlantAttribute>,
    /** A rule sends it there when it reaches this state. */
    val ruleState: String?,
    val updatedAt: Instant?,
)

data class DistributionMessage(
    val id: UUID,
    val targetId: UUID,
    val objectId: UUID,
    val number: String,
    val version: String,
    /** PENDING, SENT, ACKNOWLEDGED, REJECTED, FAILED. */
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val answer: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
    val answeredAt: Instant?,
    val payload: Map<String, Any?>,
)

data class Acknowledge(
    val ok: Boolean,
    val message: String? = null,
    /** The ERP's material or document number. */
    val erpKey: String? = null,
)

/**
 * ERP distribution (roadmap 9.5). Targets are an ERP system and a plant, with plant-specific
 * attributes; rules say which objects (by type, context and state) go to which targets. When an
 * object reaches a rule's state, a message (the object, its attributes and plant values, and a
 * part's single-level BOM) goes into the outbox in the same transaction. Messages are POSTed to the
 * target's endpoint, signed (`X-PLM-Signature`, HMAC-SHA256 of `timestamp.body`), with retries; or,
 * without an endpoint, the ERP fetches them. The ERP acknowledges each (accepted with its own key,
 * or rejected with a reason), and the object shows its status per target, with resend.
 *
 * Targets and rules: site administrators. Object status and plant values: readers and editors
 * of the object. Acknowledging and fetching: the target's secret (`X-PLM-Target-Secret`).
 * Errors: `ADMIN_REQUIRED`, `TARGET_NOT_FOUND`, `MESSAGE_NOT_FOUND`, `INVALID_TARGET`,
 * `MISSING_PLANT_VALUES`, `INVALID_SECRET`.
 */
interface DistributionService {
    fun targets(actor: Actor): List<DistributionTarget>

    /** Returns the target and its secret (shown once). */
    fun createTarget(
        actor: Actor,
        command: SaveTarget,
    ): TargetSecret

    fun updateTarget(
        actor: Actor,
        id: UUID,
        command: SaveTarget,
    ): DistributionTarget

    fun deleteTarget(
        actor: Actor,
        id: UUID,
    )

    fun addRule(
        actor: Actor,
        targetId: UUID,
        command: SaveDistributionRule,
    ): DistributionTarget

    fun removeRule(
        actor: Actor,
        ruleId: UUID,
    )

    /** Every target, and where [objectId] stands at each (needs read access). */
    fun status(
        actor: Actor,
        objectId: UUID,
    ): List<ObjectDistribution>

    fun setPlantValues(
        actor: Actor,
        objectId: UUID,
        targetId: UUID,
        values: Map<String, String>,
    ): ObjectDistribution

    /** Sends the object's latest iteration to [targetId] now, rule or not. */
    fun send(
        actor: Actor,
        objectId: UUID,
        targetId: UUID,
    ): ObjectDistribution

    fun messages(
        actor: Actor,
        objectId: UUID?,
        targetId: UUID?,
    ): List<DistributionMessage>

    /** For an ERP that fetches: its pending messages, then marked sent. */
    fun fetch(
        targetId: UUID,
        secret: String,
        limit: Int,
    ): List<DistributionMessage>

    fun acknowledge(
        messageId: UUID,
        secret: String,
        answer: Acknowledge,
    ): DistributionMessage
}
