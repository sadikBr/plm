package com.plm.access.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

data class LabelValue(
    val key: String,
    val label: String,
)

/** A security label (export control, proprietary...) with ordered values, least restrictive first. */
data class SecurityLabel(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val values: List<LabelValue>,
)

data class SaveSecurityLabel(
    val key: String,
    val name: String,
    val description: String? = null,
    /** On an update, the existing values must stay first and in order: new ones are appended. */
    val values: List<LabelValue>,
)

data class ObjectLabel(
    val labelId: UUID,
    val labelKey: String,
    val labelName: String,
    val valueKey: String,
    val valueLabel: String,
    val setAt: Instant,
    val setBy: String,
)

data class Clearance(
    val principalId: String,
    val labelId: UUID,
    val valueKey: String,
    val expiresAt: Instant?,
    val grantedAt: Instant,
    val grantedBy: String,
)

data class GrantClearance(
    val principalId: String,
    val labelId: UUID,
    val valueKey: String,
    val expiresAt: Instant? = null,
)

/** A time-limited grant to named people, for named objects or contexts, up to a label value. */
data class Agreement(
    val id: UUID,
    val name: String,
    val description: String?,
    val labelId: UUID,
    val valueKey: String,
    val validFrom: Instant,
    val validTo: Instant?,
    val active: Boolean,
    val principals: List<String>,
    val objectIds: List<UUID>,
    val contextIds: List<UUID>,
    val createdAt: Instant,
    val createdBy: String,
)

data class SaveAgreement(
    val name: String,
    val description: String? = null,
    val labelId: UUID,
    val valueKey: String,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
    val active: Boolean = true,
    val principals: List<String>,
    val objectIds: List<UUID> = emptyList(),
    val contextIds: List<UUID> = emptyList(),
)

/** One label on an object, and whether the person passes it. */
data class LabelCheck(
    val labelName: String,
    val value: String,
    val passed: Boolean,
    /** What lets them through: their clearance, or an agreement's name. */
    val through: String?,
    /** Their own clearance for this label, if any. */
    val clearance: String?,
)

/** Why someone can or can't see an object, as far as labels go. */
data class LabelExplanation(
    val objectId: UUID,
    val blocked: Boolean,
    val checks: List<LabelCheck>,
)

/**
 * Security labels and agreements (roadmap 9.7). An object may carry a value of each label; to
 * read it (and so to download its files) a person needs, for every label on it, a clearance at
 * that value or above, or an active agreement naming them that covers the object (or a context it
 * is in) up to that value. The access service applies this in every check and list filter;
 * site administrators (and the system) pass. Labels, clearances and agreements: site
 * administrators. Labels on an object: managers of its context.
 *
 * Errors: `ADMIN_REQUIRED`, `INVALID_SECURITY_LABEL`, `SECURITY_LABEL_NOT_FOUND`,
 * `INVALID_AGREEMENT`, `AGREEMENT_NOT_FOUND`, `LABEL_CLEARANCE_REQUIRED`.
 */
interface SecurityLabelService {
    fun labels(actor: Actor): List<SecurityLabel>

    fun createLabel(
        actor: Actor,
        command: SaveSecurityLabel,
    ): SecurityLabel

    fun updateLabel(
        actor: Actor,
        id: UUID,
        command: SaveSecurityLabel,
    ): SecurityLabel

    /** The labels on an object; [target] is the object (with its context), to check MANAGE when setting. */
    fun objectLabels(objectId: UUID): List<ObjectLabel>

    /** Sets (value) or removes (null) labels on an object. Needs MANAGE on [target]'s context. */
    fun setObjectLabels(
        actor: Actor,
        target: AccessTarget,
        values: Map<UUID, String?>,
    ): List<ObjectLabel>

    fun clearances(
        actor: Actor,
        principalId: String? = null,
    ): List<Clearance>

    fun grant(
        actor: Actor,
        command: GrantClearance,
    ): Clearance

    fun revoke(
        actor: Actor,
        principalId: String,
        labelId: UUID,
    )

    fun agreements(actor: Actor): List<Agreement>

    fun createAgreement(
        actor: Actor,
        command: SaveAgreement,
    ): Agreement

    fun updateAgreement(
        actor: Actor,
        id: UUID,
        command: SaveAgreement,
    ): Agreement

    fun deleteAgreement(
        actor: Actor,
        id: UUID,
    )

    /** Why [actor] can or can't see [target] (an object), label by label. */
    fun explain(
        actor: Actor,
        target: AccessTarget,
    ): LabelExplanation
}

/** Labels, clearances and agreements change who may see what: audited (roadmap 9.7). */
data class SecurityLabelsChanged(
    val subjectId: UUID,
    /** e.g. `label EXPORT_CONTROL saved`, `clearance granted to bob: EXPORT_CONTROL ≤ ITAR`. */
    val change: String,
) : DomainEvent {
    override val eventType = "access.SecurityLabelsChanged.v1"
    override val aggregateType = "security-label"
    override val aggregateId = subjectId
}
