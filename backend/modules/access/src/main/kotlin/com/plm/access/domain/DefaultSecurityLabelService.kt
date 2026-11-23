package com.plm.access.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Agreement
import com.plm.access.api.Clearance
import com.plm.access.api.GrantClearance
import com.plm.access.api.LabelCheck
import com.plm.access.api.LabelExplanation
import com.plm.access.api.ObjectLabel
import com.plm.access.api.Permission
import com.plm.access.api.SaveAgreement
import com.plm.access.api.SaveSecurityLabel
import com.plm.access.api.SecurityLabel
import com.plm.access.api.SecurityLabelService
import com.plm.access.api.SecurityLabelsChanged
import com.plm.access.infra.AccessRepository
import com.plm.access.infra.SecurityLabelRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

private val KEY = Regex("^[A-Z][A-Z0-9_]{0,39}$")

@Service
@Transactional
internal class DefaultSecurityLabelService(
    private val repository: SecurityLabelRepository,
    private val access: AccessService,
    private val accessRepository: AccessRepository,
    private val events: DomainEvents,
    private val clock: Clock,
) : SecurityLabelService {
    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators manage security labels",
            )
        }
    }

    private fun label(
        actor: Actor,
        id: UUID,
    ) = repository.label(id)?.takeIf { l -> repository.labels(actor.tenant.value).any { it.id == l.id } }
        ?: throw NotFoundException("SECURITY_LABEL_NOT_FOUND", "Security label $id does not exist", mapOf("id" to id))

    /** The value's rank (1 = least restrictive). */
    private fun rank(
        l: SecurityLabel,
        valueKey: String,
    ): Int {
        val i = l.values.indexOfFirst { it.key == valueKey }
        if (i < 0) throw ValidationException("INVALID_SECURITY_LABEL", "${l.name} has no value $valueKey")
        return i + 1
    }

    private fun display(
        l: SecurityLabel,
        key: String,
    ) = l.values.firstOrNull { it.key == key }?.label ?: key

    @Transactional(readOnly = true)
    override fun labels(actor: Actor) = repository.labels(actor.tenant.value)

    private fun validate(command: SaveSecurityLabel) {
        val errors = mutableListOf<String>()
        if (!KEY.matches(command.key)) errors += "The key is upper case letters, digits and _ (e.g. EXPORT_CONTROL)"
        if (command.name.isBlank()) errors += "Give the label a name"
        if (command.values.isEmpty()) errors += "Give the label at least one value"
        if (command.values.any { !KEY.matches(it.key) || it.label.isBlank() }) {
            errors +=
                "Each value needs a key (e.g. ITAR) and a label"
        }
        if (command.values
                .map { it.key }
                .toSet()
                .size != command.values.size
        ) {
            errors += "Value keys must differ"
        }
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_SECURITY_LABEL",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
    }

    override fun createLabel(
        actor: Actor,
        command: SaveSecurityLabel,
    ): SecurityLabel {
        requireAdmin(actor)
        validate(command)
        val l =
            SecurityLabel(
                UuidV7.generate(
                    clock,
                ),
                command.key,
                command.name.trim(),
                command.description?.trim()?.ifEmpty {
                    null
                },
                command.values,
            )
        try {
            repository.saveLabel(actor.tenant.value, l, clock.instant())
        } catch (e: DuplicateKeyException) {
            throw ValidationException(
                "INVALID_SECURITY_LABEL",
                "There is already a label ${command.key}",
            ).apply { initCause(e) }
        }
        events.publish(actor, SecurityLabelsChanged(l.id, "label ${l.key} created"))
        return l
    }

    override fun updateLabel(
        actor: Actor,
        id: UUID,
        command: SaveSecurityLabel,
    ): SecurityLabel {
        requireAdmin(actor)
        validate(command)
        val old = label(actor, id)
        // Ranks are stored with objects and clearances: values keep their places, new ones go last.
        if (command.values.size < old.values.size ||
            command.values.take(old.values.size).map { it.key } != old.values.map { it.key }
        ) {
            throw ValidationException(
                "INVALID_SECURITY_LABEL",
                "Existing values stay, in order: add new values at the end",
            )
        }
        val l =
            old.copy(
                name = command.name.trim(),
                description =
                    command.description?.trim()?.ifEmpty {
                        null
                    },
                values = command.values,
            )
        repository.saveLabel(actor.tenant.value, l, clock.instant())
        events.publish(actor, SecurityLabelsChanged(l.id, "label ${l.key} changed"))
        return l
    }

    @Transactional(readOnly = true)
    override fun objectLabels(objectId: UUID): List<ObjectLabel> = repository.objectLabels(objectId)

    override fun setObjectLabels(
        actor: Actor,
        target: AccessTarget,
        values: Map<UUID, String?>,
    ): List<ObjectLabel> {
        val objectId = target.objectId ?: throw ValidationException("INVALID_SECURITY_LABEL", "Which object?")
        if (!actor.siteAdmin) access.require(actor, AccessTarget(target.contextId), Permission.MANAGE)
        for ((labelId, valueKey) in values) {
            val l = label(actor, labelId)
            if (valueKey == null) {
                repository.removeObjectLabel(objectId, labelId)
                events.publish(actor, SecurityLabelsChanged(objectId, "label ${l.key} removed"))
            } else {
                repository.setObjectLabel(
                    actor.tenant.value,
                    objectId,
                    labelId,
                    valueKey,
                    rank(l, valueKey),
                    clock.instant(),
                    actor.principal.value,
                )
                events.publish(actor, SecurityLabelsChanged(objectId, "marked ${l.key}: $valueKey"))
            }
        }
        return repository.objectLabels(objectId)
    }

    @Transactional(readOnly = true)
    override fun clearances(
        actor: Actor,
        principalId: String?,
    ): List<Clearance> {
        if (principalId != actor.principal.value) requireAdmin(actor)
        return repository.clearances(actor.tenant.value, principalId)
    }

    override fun grant(
        actor: Actor,
        command: GrantClearance,
    ): Clearance {
        requireAdmin(actor)
        val l = label(actor, command.labelId)
        if (command.principalId.isBlank()) throw ValidationException("INVALID_SECURITY_LABEL", "Who is cleared?")
        val c =
            Clearance(
                command.principalId.trim(),
                l.id,
                command.valueKey,
                command.expiresAt,
                clock.instant(),
                actor.principal.value,
            )
        repository.grant(actor.tenant.value, c, rank(l, command.valueKey))
        events.publish(
            actor,
            SecurityLabelsChanged(l.id, "clearance granted to ${c.principalId}: ${l.key} up to ${c.valueKey}"),
        )
        return c
    }

    override fun revoke(
        actor: Actor,
        principalId: String,
        labelId: UUID,
    ) {
        requireAdmin(actor)
        val l = label(actor, labelId)
        repository.revoke(principalId, labelId)
        events.publish(actor, SecurityLabelsChanged(l.id, "clearance of $principalId for ${l.key} revoked"))
    }

    @Transactional(readOnly = true)
    override fun agreements(actor: Actor): List<Agreement> {
        requireAdmin(actor)
        return repository.agreements(actor.tenant.value)
    }

    private fun save(
        actor: Actor,
        id: UUID?,
        command: SaveAgreement,
    ): Agreement {
        requireAdmin(actor)
        val l = label(actor, command.labelId)
        val errors = mutableListOf<String>()
        if (command.name.isBlank()) errors += "Give the agreement a name"
        if (command.principals.isEmpty()) errors += "Name the people it covers"
        if (command.objectIds.isEmpty() &&
            command.contextIds.isEmpty()
        ) {
            errors += "Choose the objects or contexts it covers"
        }
        val from = command.validFrom ?: clock.instant()
        if (command.validTo != null && !command.validTo.isAfter(from)) errors += "It must end after it starts"
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_AGREEMENT",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
        val existing =
            id?.let {
                repository.agreement(it)
                    ?: throw NotFoundException("AGREEMENT_NOT_FOUND", "Agreement $it does not exist")
            }
        val a =
            Agreement(
                id ?: UuidV7.generate(clock),
                command.name.trim(),
                command.description?.trim()?.ifEmpty { null },
                l.id,
                command.valueKey,
                from,
                command.validTo,
                command.active,
                command.principals.map { it.trim() }.filter { it.isNotEmpty() },
                command.objectIds,
                command.contextIds,
                existing?.createdAt ?: clock.instant(),
                existing?.createdBy ?: actor.principal.value,
            )
        repository.saveAgreement(actor.tenant.value, a, rank(l, command.valueKey))
        events.publish(
            actor,
            SecurityLabelsChanged(
                a.id,
                "agreement '${a.name}' saved: ${l.key} up to ${a.valueKey} for ${a.principals.joinToString()}",
            ),
        )
        return a
    }

    override fun createAgreement(
        actor: Actor,
        command: SaveAgreement,
    ) = save(actor, null, command)

    override fun updateAgreement(
        actor: Actor,
        id: UUID,
        command: SaveAgreement,
    ) = save(actor, id, command)

    override fun deleteAgreement(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        if (repository.deleteAgreement(id) ==
            0
        ) {
            throw NotFoundException("AGREEMENT_NOT_FOUND", "Agreement $id does not exist")
        }
        events.publish(actor, SecurityLabelsChanged(id, "agreement deleted"))
    }

    @Transactional(readOnly = true)
    override fun explain(
        actor: Actor,
        target: AccessTarget,
    ): LabelExplanation {
        val objectId = target.objectId ?: throw ValidationException("INVALID_SECURITY_LABEL", "Which object?")
        // Only for people the context itself lets in: the labels are what the explanation is about.
        val bits =
            accessRepository.permissionBits(
                actor.tenant,
                actor.principal,
                target.contextId,
                target.typeId,
                target.state,
            )
        if (!actor.siteAdmin && bits and Permission.READ.bit == 0) {
            throw NotFoundException("OBJECT_NOT_FOUND", "Object $objectId does not exist")
        }
        val now = clock.instant()
        val mine =
            repository.clearances(actor.tenant.value, actor.principal.value).filter {
                it.expiresAt == null ||
                    it.expiresAt.isAfter(now)
            }
        val labels = repository.labels(actor.tenant.value).associateBy { it.id }
        val checks =
            repository.objectLabels(objectId).map { ol ->
                val l = labels.getValue(ol.labelId)
                val needed = rank(l, ol.valueKey)
                val clearance = mine.firstOrNull { it.labelId == ol.labelId }
                val clearedRank = clearance?.let { rank(l, it.valueKey) } ?: 0
                val agreements =
                    repository.coveringAgreements(
                        actor.tenant.value,
                        actor.principal.value,
                        ol.labelId,
                        needed,
                        objectId,
                        target.contextId.value,
                        now,
                    )
                val through =
                    when {
                        actor.siteAdmin -> "site administrator"
                        clearedRank >= needed -> "clearance ${display(l, clearance!!.valueKey)}"
                        agreements.isNotEmpty() -> agreements.first()
                        else -> null
                    }
                LabelCheck(l.name, ol.valueLabel, through != null, through, clearance?.let { display(l, it.valueKey) })
            }
        return LabelExplanation(objectId, checks.any { !it.passed }, checks)
    }
}
