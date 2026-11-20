package com.plm.extensions.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

/**
 * A rule that refuses an operation (roadmap 2.5): when [condition] (CEL over `object`, `user`,
 * `event`) is true for an object of one of [types] (subtypes included; empty: all), the
 * [operations] are refused with [message].
 */
data class OperationRule(
    /** A UUID for the site's rules, `<extension>.<rule>` for an extension's. */
    val id: String,
    val name: String,
    /** e.g. `object.check-in`, `object.set-state`. */
    val operations: List<String>,
    val types: List<String>,
    /** Refuse when this is true. */
    val condition: String,
    val message: String,
    val active: Boolean,
    /** `site`, or the extension that declared it (then it's changed by upgrading the extension). */
    val source: String,
    val updatedAt: Instant?,
    val updatedBy: String?,
)

data class OperationRuleSettings(
    val name: String,
    val operations: List<String>,
    val types: List<String> = emptyList(),
    val condition: String,
    val message: String,
    val active: Boolean = true,
)

/** A rule in an extension's manifest (`rules:`). */
data class ManifestRule(
    val id: String,
    val name: String? = null,
    /** Operations it applies to, e.g. `[object.check-in]`. */
    val on: List<String>,
    val appliesTo: AppliesTo = AppliesTo(),
    /** Refuse when this is true. */
    val `when`: String,
    /** The message the user sees. */
    val reject: String,
)

/**
 * The site's operation rules, and the extensions' alongside (read-only here). Reading is for site
 * administrators, like changing (`ADMIN_REQUIRED`). Error codes: `RULE_NOT_FOUND`, `INVALID_RULE`,
 * `INVALID_EXPRESSION`, `UNKNOWN_TYPE`, `DUPLICATE_RULE_NAME`.
 */
interface OperationRuleService {
    fun list(actor: Actor): List<OperationRule>

    fun define(
        actor: Actor,
        settings: OperationRuleSettings,
    ): OperationRule

    fun update(
        actor: Actor,
        id: UUID,
        settings: OperationRuleSettings,
    ): OperationRule

    fun delete(
        actor: Actor,
        id: UUID,
    )
}

data class OperationRuleChanged(
    val ruleId: UUID,
    val name: String,
    val condition: String?,
    val deleted: Boolean = false,
) : DomainEvent {
    override val eventType = "extensions.OperationRuleChanged.v1"
    override val aggregateType = "operation-rule"
    override val aggregateId: UUID = ruleId
}
