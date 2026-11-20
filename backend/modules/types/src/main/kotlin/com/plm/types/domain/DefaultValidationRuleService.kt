package com.plm.types.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.expression.ExpressionKind
import com.plm.platform.expression.Expressions
import com.plm.platform.id.UuidV7
import com.plm.types.api.AttributeService
import com.plm.types.api.TypeId
import com.plm.types.api.ValidationRule
import com.plm.types.api.ValidationRuleChanged
import com.plm.types.api.ValidationRuleId
import com.plm.types.api.ValidationRuleService
import com.plm.types.api.ValidationRuleSettings
import com.plm.types.infra.AttributeRepository
import com.plm.types.infra.ValidationRuleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val MAX_NAME = 80
private const val MAX_MESSAGE = 300

@Service
@Transactional
internal class DefaultValidationRuleService(
    private val rules: ValidationRuleRepository,
    private val types: AttributeRepository,
    private val attributes: AttributeService,
    private val events: DomainEvents,
    private val clock: Clock,
) : ValidationRuleService {
    @Transactional(readOnly = true)
    override fun rules(
        actor: Actor,
        typeId: TypeId,
    ): List<ValidationRule> {
        val lineage = lineage(actor, typeId)
        val byType = rules.forTypes(actor.tenant, lineage).groupBy { it.typeId }
        return lineage.flatMap { byType[it].orEmpty() }
    }

    override fun define(
        actor: Actor,
        typeId: TypeId,
        settings: ValidationRuleSettings,
    ): ValidationRule {
        requireAdmin(actor)
        lineage(actor, typeId)
        val rule =
            ValidationRule(
                id = ValidationRuleId(UuidV7.generate(clock)),
                typeId = typeId,
                name = "",
                condition = "",
                message = "",
                attribute = null,
                active = true,
                order = 0,
                updatedAt = clock.instant(),
                updatedBy = actor.principal.value,
            ).applying(actor, settings)
        rules.save(actor.tenant, rule)
        events.publish(actor, ValidationRuleChanged(rule.id.value, typeId.value, rule.name, rule.condition))
        return rule
    }

    override fun update(
        actor: Actor,
        id: ValidationRuleId,
        settings: ValidationRuleSettings,
    ): ValidationRule {
        requireAdmin(actor)
        val rule =
            find(
                actor,
                id,
            ).applying(actor, settings).copy(updatedAt = clock.instant(), updatedBy = actor.principal.value)
        rules.save(actor.tenant, rule)
        events.publish(actor, ValidationRuleChanged(rule.id.value, rule.typeId.value, rule.name, rule.condition))
        return rule
    }

    override fun delete(
        actor: Actor,
        id: ValidationRuleId,
    ) {
        requireAdmin(actor)
        val rule = find(actor, id)
        rules.delete(actor.tenant, id)
        events.publish(actor, ValidationRuleChanged(rule.id.value, rule.typeId.value, rule.name, null, deleted = true))
    }

    private fun ValidationRule.applying(
        actor: Actor,
        s: ValidationRuleSettings,
    ): ValidationRule {
        val name = s.name.trim()
        val message = s.message.trim()
        val condition = s.condition.trim()
        if (name.isEmpty() || name.length > MAX_NAME) {
            throw ValidationException("INVALID_RULE", "A rule needs a name of at most $MAX_NAME characters")
        }
        if (message.isEmpty() || message.length > MAX_MESSAGE) {
            throw ValidationException(
                "INVALID_RULE",
                "A rule needs a message of at most $MAX_MESSAGE characters: it's what users see",
            )
        }
        val problems = Expressions.check(condition, ExpressionKind.CONDITION)
        if (problems.isNotEmpty()) {
            throw ValidationException(
                "INVALID_EXPRESSION",
                "The condition isn't valid: ${problems.first().message}",
                mapOf(
                    "problems" to
                        problems.map { mapOf("message" to it.message, "line" to it.line, "column" to it.column) },
                ),
            )
        }
        val attribute = s.attribute?.trim()?.ifEmpty { null }
        if (attribute != null &&
            attributes.effective(actor, typeId, includeInactive = true).none { it.name == attribute }
        ) {
            throw ValidationException(
                "UNKNOWN_ATTRIBUTE",
                "The type has no attribute '$attribute'",
                mapOf(
                    "attribute" to attribute,
                ),
            )
        }
        return copy(
            name = name,
            condition = condition,
            message = message,
            attribute = attribute,
            active = s.active,
            order = s.order,
        )
    }

    private fun find(
        actor: Actor,
        id: ValidationRuleId,
    ) = rules.find(actor.tenant, id)
        ?: throw NotFoundException("RULE_NOT_FOUND", "Validation rule $id does not exist", mapOf("ruleId" to id.value))

    private fun lineage(
        actor: Actor,
        typeId: TypeId,
    ) = types.lineage(actor.tenant, typeId).ifEmpty {
        throw NotFoundException("TYPE_NOT_FOUND", "Type $typeId does not exist", mapOf("typeId" to typeId.value))
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators can change validation rules",
            )
        }
    }
}
