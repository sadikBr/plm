package com.plm.types.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

@JvmInline
value class ValidationRuleId(
    val value: UUID,
) {
    override fun toString() = value.toString()
}

/**
 * A condition every object of [typeId] (and its subtypes) must meet to be created or checked in.
 * [condition] is a CEL expression over `object` and `user` (see docs/DEVELOPMENT.md, Expressions):
 * the object is valid when it's true. [attribute] ties the [message] to a field in forms.
 */
data class ValidationRule(
    val id: ValidationRuleId,
    val typeId: TypeId,
    val name: String,
    val condition: String,
    val message: String,
    val attribute: String?,
    val active: Boolean,
    val order: Int,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class ValidationRuleSettings(
    val name: String,
    val condition: String,
    val message: String,
    val attribute: String? = null,
    val active: Boolean = true,
    val order: Int = 0,
)

/**
 * Validation rules per type (roadmap 2.2). Reading is open to everyone (forms explain them);
 * changes are for site administrators.
 *
 * Error codes: `ADMIN_REQUIRED`, `TYPE_NOT_FOUND`, `RULE_NOT_FOUND`, `INVALID_RULE`,
 * `INVALID_EXPRESSION`, `UNKNOWN_ATTRIBUTE`, `DUPLICATE_RULE_NAME`.
 */
interface ValidationRuleService {
    /** The rules that apply to [typeId]: its ancestors' first (root down), then its own, by order. */
    fun rules(
        actor: Actor,
        typeId: TypeId,
    ): List<ValidationRule>

    fun define(
        actor: Actor,
        typeId: TypeId,
        settings: ValidationRuleSettings,
    ): ValidationRule

    fun update(
        actor: Actor,
        id: ValidationRuleId,
        settings: ValidationRuleSettings,
    ): ValidationRule

    fun delete(
        actor: Actor,
        id: ValidationRuleId,
    )
}

data class ValidationRuleChanged(
    val ruleId: UUID,
    val typeId: UUID,
    val name: String,
    val condition: String?,
    val deleted: Boolean = false,
) : DomainEvent {
    override val eventType = "types.ValidationRuleChanged.v1"
    override val aggregateType = "type"
    override val aggregateId: UUID = typeId
}
