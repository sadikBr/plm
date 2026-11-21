package com.plm.bom.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Permission
import com.plm.bom.api.AddAlternate
import com.plm.bom.api.AddSubstitute
import com.plm.bom.api.Alternate
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ReplacementService
import com.plm.bom.api.ReplacementsChanged
import com.plm.bom.api.Substitute
import com.plm.bom.api.SubstituteUse
import com.plm.bom.api.UsedPart
import com.plm.bom.infra.BomRepository
import com.plm.bom.infra.ReplacementRepository
import com.plm.bom.infra.Resolved
import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultReplacementService(
    private val repository: ReplacementRepository,
    private val bom: BomRepository,
    private val versioning: VersioningService,
    private val access: AccessService,
    private val types: TypeService,
    private val events: DomainEvents,
    private val clock: Clock,
) : ReplacementService {
    private fun readable(actor: Actor) =
        access.filter(actor, Permission.READ, AccessColumns("m.context_id", "m.type_id", "li.state", "m.id"))

    private fun parts(
        actor: Actor,
        ids: Collection<UUID>,
    ): Map<UUID, Resolved> = bom.resolve(ids.distinct(), ConfigSpec.LATEST, readable(actor))

    private fun Resolved?.used(id: UUID) =
        if (this == null) {
            UsedPart(id, false, null, null, null, null, null, null, false)
        } else {
            UsedPart(id, true, number, name, typeId, iterationId, version, state, hasChildren)
        }

    private fun requirePart(
        actor: Actor,
        obj: VersionedObject,
    ) {
        if (types.get(actor, obj.typeId).baseClass !=
            BaseClass.PART
        ) {
            throw ValidationException("NOT_A_PART", "${obj.number} isn't a part")
        }
    }

    @Transactional(readOnly = true)
    override fun alternates(
        actor: Actor,
        partId: MasterId,
    ): List<Alternate> {
        val part = versioning.get(actor, partId)
        val rows = repository.alternates(part.id.value)
        val found = parts(actor, rows.map { it.alternateId })
        return rows.map {
            Alternate(found[it.alternateId].used(it.alternateId), it.twoWay, it.notes, it.createdAt, it.createdBy)
        }
    }

    override fun addAlternate(
        actor: Actor,
        partId: MasterId,
        request: AddAlternate,
    ): List<Alternate> {
        val part = versioning.get(actor, partId)
        access.require(
            actor,
            AccessTarget(part.contextId, part.typeId, part.latest.state, part.id.value),
            Permission.MODIFY,
        )
        val other = versioning.get(actor, MasterId(request.alternateId))
        requirePart(actor, part)
        requirePart(actor, other)
        if (other.id == part.id) throw ValidationException("INVALID_REPLACEMENT", "A part can't be its own alternate")
        val notes =
            request.notes
                ?.trim()
                ?.ifEmpty { null }
                ?.take(1000)
        repository.addAlternate(
            actor.tenant,
            part.id.value,
            other.id.value,
            notes,
            clock.instant(),
            actor.principal.value,
        )
        if (request.twoWay) {
            access.require(
                actor,
                AccessTarget(other.contextId, other.typeId, other.latest.state, other.id.value),
                Permission.MODIFY,
            )
            repository.addAlternate(
                actor.tenant,
                other.id.value,
                part.id.value,
                notes,
                clock.instant(),
                actor.principal.value,
            )
        }
        events.publish(actor, ReplacementsChanged(part.id.value, "ALTERNATE_ADDED", other.id.value, null))
        return alternates(actor, partId)
    }

    override fun removeAlternate(
        actor: Actor,
        partId: MasterId,
        alternateId: UUID,
    ) {
        val part = versioning.get(actor, partId)
        access.require(
            actor,
            AccessTarget(part.contextId, part.typeId, part.latest.state, part.id.value),
            Permission.MODIFY,
        )
        if (repository.removeAlternate(part.id.value, alternateId) ==
            0
        ) {
            throw NotFoundException("REPLACEMENT_NOT_FOUND", "It isn't an alternate")
        }
        events.publish(actor, ReplacementsChanged(part.id.value, "ALTERNATE_REMOVED", alternateId, null))
    }

    /** The line, if it belongs to an iteration of [parentId] the actor may read. */
    private fun line(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
    ): com.plm.bom.infra.LinkRow {
        val parent = versioning.get(actor, parentId)
        val row = bom.link(usageId)
        if (row == null || bom.mastersOf(listOf(row.parentIterationId))[row.parentIterationId] != parent.id.value) {
            throw NotFoundException("USAGE_NOT_FOUND", "There's no such line")
        }
        return row
    }

    @Transactional(readOnly = true)
    override fun substitutes(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
    ): List<Substitute> {
        line(actor, parentId, usageId)
        val rows = repository.substitutes(usageId)
        val found = parts(actor, rows.map { it.substituteId })
        return rows.map { Substitute(found[it.substituteId].used(it.substituteId), it.quantity, it.notes) }
    }

    override fun addSubstitute(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
        request: AddSubstitute,
    ): List<Substitute> {
        val wc = versioning.workingCopy(actor, parentId).id.value
        val row = line(actor, parentId, usageId)
        if (row.parentIterationId !=
            wc
        ) {
            throw NotFoundException("USAGE_NOT_FOUND", "There's no such line in your working copy")
        }
        val sub = versioning.get(actor, MasterId(request.substituteId))
        requirePart(actor, sub)
        if (sub.id.value ==
            row.childMasterId
        ) {
            throw ValidationException("INVALID_REPLACEMENT", "A part can't substitute for itself")
        }
        if (bom.reaches(
                sub.id.value,
                parentId.value,
            )
        ) {
            throw ValidationException("INVALID_REPLACEMENT", "${sub.number} uses this part: it can't be under it")
        }
        if (request.quantity != null &&
            request.quantity.signum() <= 0
        ) {
            throw ValidationException("INVALID_REPLACEMENT", "The quantity must be more than 0")
        }
        repository.addSubstitute(
            usageId,
            sub.id.value,
            request.quantity,
            request.notes
                ?.trim()
                ?.ifEmpty { null }
                ?.take(1000),
        )
        events.publish(actor, ReplacementsChanged(parentId.value, "SUBSTITUTE_ADDED", sub.id.value, usageId))
        return substitutes(actor, parentId, usageId)
    }

    override fun removeSubstitute(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
        substituteId: UUID,
    ) {
        val wc = versioning.workingCopy(actor, parentId).id.value
        val row = line(actor, parentId, usageId)
        if (row.parentIterationId !=
            wc
        ) {
            throw NotFoundException("USAGE_NOT_FOUND", "There's no such line in your working copy")
        }
        if (repository.removeSubstitute(usageId, substituteId) ==
            0
        ) {
            throw NotFoundException("REPLACEMENT_NOT_FOUND", "It isn't a substitute")
        }
        events.publish(actor, ReplacementsChanged(parentId.value, "SUBSTITUTE_REMOVED", substituteId, usageId))
    }

    @Transactional(readOnly = true)
    override fun substituteUses(
        actor: Actor,
        partId: MasterId,
    ): List<SubstituteUse> {
        val part = versioning.get(actor, partId)
        val rows = repository.substituteUses(part.id.value, readable(actor))
        val found = parts(actor, rows.flatMap { listOf(it.parentMasterId, it.originalId) })
        return rows.map {
            SubstituteUse(
                found[it.parentMasterId].used(it.parentMasterId),
                it.lineNumber,
                found[it.originalId].used(it.originalId),
                it.quantity,
                it.unit,
            )
        }
    }
}
