package com.plm.types.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.types.api.CreateNumberingScheme
import com.plm.types.api.NumberingScheme
import com.plm.types.api.NumberingSchemeId
import com.plm.types.api.RevisionScheme
import com.plm.types.api.TypeId
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeRuleSettings
import com.plm.types.api.TypeRules
import com.plm.types.api.TypeRulesChanged
import com.plm.types.infra.AttributeRepository
import com.plm.types.infra.TypeRuleRepository
import com.plm.types.infra.TypeRuleRow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val AFFIX = Regex("^[A-Za-z0-9._-]{0,20}$")

@Service
@Transactional
internal class DefaultTypeRuleService(
    private val rules: TypeRuleRepository,
    private val types: AttributeRepository,
    private val events: DomainEvents,
) : TypeRuleService {
    @Transactional(readOnly = true)
    override fun rules(
        actor: Actor,
        typeId: TypeId,
    ): TypeRules {
        val lineage = lineage(actor, typeId)
        val own = rules.rules(lineage).associateBy { it.typeId }
        // Walk from the type up to the root; the first type that sets a rule wins.
        val nearestFirst = lineage.reversed()
        val origin = mutableMapOf<String, TypeId>()

        fun <T> resolve(
            key: String,
            pick: (TypeRuleRow) -> T?,
        ): T? = nearestFirst.firstNotNullOfOrNull { type -> own[type]?.let(pick)?.also { origin[key] = type } }

        return TypeRules(
            typeId = typeId,
            numberingSchemeId = resolve("numberingScheme") { it.numberingSchemeId },
            revisionScheme = resolve("revisionScheme") { it.revisionScheme } ?: RevisionScheme.ALPHABETIC_ASME,
            allowManualNumber = resolve("allowManualNumber") { it.allowManualNumber } ?: true,
            inheritedFrom = origin,
        )
    }

    override fun updateRules(
        actor: Actor,
        typeId: TypeId,
        settings: TypeRuleSettings,
    ): TypeRules {
        requireAdmin(actor)
        lineage(actor, typeId)
        settings.numberingSchemeId?.let { scheme(actor, it) }
        rules.upsert(
            actor.tenant,
            TypeRuleRow(typeId, settings.numberingSchemeId, settings.revisionScheme, settings.allowManualNumber),
        )
        val effective = rules(actor, typeId)
        events.publish(
            actor,
            TypeRulesChanged(
                typeId.value,
                settings.numberingSchemeId?.value,
                settings.revisionScheme?.name,
                settings.allowManualNumber,
            ),
        )
        return effective
    }

    @Transactional(readOnly = true)
    override fun numberingSchemes(actor: Actor): List<NumberingScheme> = rules.schemes(actor.tenant)

    override fun createNumberingScheme(
        actor: Actor,
        command: CreateNumberingScheme,
    ): NumberingScheme {
        requireAdmin(actor)
        val errors = mutableListOf<String>()
        if (command.name.isBlank() || command.name.length > 100) errors += "Name must be 1-100 characters"
        if (!AFFIX.matches(command.prefix)) errors += "Prefix: up to 20 letters, digits, '.', '_' or '-'"
        if (!AFFIX.matches(command.suffix)) errors += "Suffix: up to 20 letters, digits, '.', '_' or '-'"
        if (command.padding !in 1..18) errors += "Padding must be 1-18 digits"
        if (command.start < 0) errors += "Start must not be negative"
        if (errors.isNotEmpty()) {
            throw ValidationException("INVALID_NUMBERING_SCHEME", errors.joinToString("; "), mapOf("errors" to errors))
        }
        val scheme =
            NumberingScheme(
                NumberingSchemeId(UuidV7.generate()),
                command.name.trim(),
                command.prefix,
                command.padding,
                command.suffix,
                command.start,
            )
        rules.insertScheme(actor.tenant, scheme)
        return scheme
    }

    override fun nextNumber(
        actor: Actor,
        scheme: NumberingSchemeId,
    ): String {
        val definition = scheme(actor, scheme)
        val value = rules.takeNext(actor.tenant, scheme) ?: error("numbering scheme $scheme vanished")
        return definition.format(value)
    }

    private fun scheme(
        actor: Actor,
        id: NumberingSchemeId,
    ) = rules.scheme(actor.tenant, id)
        ?: throw NotFoundException(
            "NUMBERING_SCHEME_NOT_FOUND",
            "Numbering scheme $id does not exist",
            mapOf(
                "schemeId" to id.value,
            ),
        )

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
                "Only site administrators can change type rules",
            )
        }
    }
}
