package com.plm.extensions.domain

import com.plm.extensions.api.OperationRule
import com.plm.extensions.api.OperationRuleChanged
import com.plm.extensions.api.OperationRuleService
import com.plm.extensions.api.OperationRuleSettings
import com.plm.extensions.infra.ExtensionRepository
import com.plm.extensions.infra.OperationRuleRepository
import com.plm.platform.actor.Actor
import com.plm.platform.contract.PlatformContract
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.expression.ExpressionException
import com.plm.platform.expression.ExpressionKind
import com.plm.platform.expression.Expressions
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeService
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.Refusal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

private const val MAX_NAME = 80
private const val MAX_MESSAGE = 300

/**
 * Operation rules (roadmap 2.5): the site's, kept here, and those of active extensions, read from
 * their manifests. As an [OperationGuard], refuses an operation when a rule's condition holds. A
 * rule whose condition can't be evaluated refuses too: rules fail closed.
 */
@Service
@Transactional
internal class OperationRules(
    private val rules: OperationRuleRepository,
    private val extensions: ExtensionRepository,
    private val types: TypeService,
    private val events: DomainEvents,
    private val clock: Clock,
) : OperationRuleService,
    OperationGuard {
    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<OperationRule> {
        requireAdmin(actor)
        return all(actor.tenant)
    }

    override fun define(
        actor: Actor,
        settings: OperationRuleSettings,
    ): OperationRule {
        requireAdmin(actor)
        val rule = validated(actor, UuidV7.generate(clock), settings)
        rules.save(actor.tenant, rule)
        events.publish(actor, OperationRuleChanged(UUID.fromString(rule.id), rule.name, rule.condition))
        return rule
    }

    override fun update(
        actor: Actor,
        id: UUID,
        settings: OperationRuleSettings,
    ): OperationRule {
        requireAdmin(actor)
        rules.find(actor.tenant, id) ?: throw notFound(id)
        val rule = validated(actor, id, settings)
        rules.save(actor.tenant, rule)
        events.publish(actor, OperationRuleChanged(id, rule.name, rule.condition))
        return rule
    }

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        val rule = rules.find(actor.tenant, id) ?: throw notFound(id)
        rules.delete(actor.tenant, id)
        events.publish(actor, OperationRuleChanged(id, rule.name, null, deleted = true))
    }

    override fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal> {
        val applicable = all(actor.tenant).filter { it.active && check.operation.key in it.operations }
        if (applicable.isEmpty()) return emptyList()

        @Suppress("UNCHECKED_CAST")
        val lineage = (check.model["object"] as Map<String, Any?>)["types"] as List<String>
        val variables = check.model + ("event" to check.event)
        return applicable
            .filter { it.types.isEmpty() || it.types.any { t -> t in lineage } }
            .mapNotNull { rule ->
                val refuse =
                    try {
                        Expressions.test(rule.condition, variables)
                    } catch (e: ExpressionException) {
                        return@mapNotNull Refusal(
                            rule.name,
                            rule.source,
                            "${rule.message} (the rule couldn't be checked: ${e.message})",
                        )
                    }
                if (refuse) Refusal(rule.name, rule.source, rule.message) else null
            }
    }

    /** The site's rules, then each active extension's. */
    private fun all(tenant: TenantId): List<OperationRule> =
        rules.all(tenant) +
            extensions
                .all(tenant)
                .filter { it.enabled && Versions.Range.parse(it.manifest.platform).includes(PlatformContract.VERSION) }
                .flatMap { row ->
                    row.manifest.rules.map { r ->
                        OperationRule(
                            id = "${row.manifest.id}.${r.id}",
                            name = r.name ?: r.id,
                            operations = r.on,
                            types = r.appliesTo.types,
                            condition = r.`when`,
                            message = r.reject,
                            active = true,
                            source = row.manifest.id,
                            updatedAt = row.updatedAt,
                            updatedBy = row.updatedBy,
                        )
                    }
                }

    private fun validated(
        actor: Actor,
        id: UUID,
        s: OperationRuleSettings,
    ): OperationRule {
        val name = s.name.trim()
        val message = s.message.trim()
        val condition = s.condition.trim()
        if (name.isEmpty() ||
            name.length > MAX_NAME
        ) {
            throw ValidationException("INVALID_RULE", "A rule needs a name of at most $MAX_NAME characters")
        }
        if (message.isEmpty() || message.length > MAX_MESSAGE) {
            throw ValidationException(
                "INVALID_RULE",
                "A rule needs a message of at most $MAX_MESSAGE characters: it's what users see",
            )
        }
        if (s.operations.isEmpty()) {
            throw ValidationException(
                "INVALID_RULE",
                "Choose the operations the rule applies to",
            )
        }
        s.operations.filter { GuardedOperation.of(it) == null }.takeIf { it.isNotEmpty() }?.let {
            throw ValidationException("INVALID_RULE", "Unknown operations: ${it.joinToString()}")
        }
        val known = types.list(actor).map { it.name }.toSet()
        s.types.filter { it !in known }.takeIf { it.isNotEmpty() }?.let {
            throw ValidationException("UNKNOWN_TYPE", "No such types: ${it.joinToString()}", mapOf("types" to it))
        }
        Expressions.check(condition, ExpressionKind.CONDITION).firstOrNull()?.let {
            throw ValidationException("INVALID_EXPRESSION", "The condition isn't valid: ${it.message}")
        }
        return OperationRule(
            id = id.toString(),
            name = name,
            operations = s.operations.distinct(),
            types = s.types.distinct(),
            condition = condition,
            message = message,
            active = s.active,
            source = "site",
            updatedAt = clock.instant(),
            updatedBy = actor.principal.value,
        )
    }

    private fun notFound(id: UUID) =
        NotFoundException("RULE_NOT_FOUND", "Rule $id does not exist", mapOf("ruleId" to id))

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators manage operation rules",
            )
        }
    }
}
