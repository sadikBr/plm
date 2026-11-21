package com.plm.bom.domain

import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.StructureChanged
import com.plm.bom.api.UpdateUsage
import com.plm.bom.api.UsageLine
import com.plm.bom.api.UsedPart
import com.plm.bom.infra.BomRepository
import com.plm.bom.infra.LinkRow
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.types.api.BaseClass
import com.plm.types.api.EnumerationService
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultBomService(
    private val repository: BomRepository,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val enumerations: EnumerationService,
    private val events: DomainEvents,
    private val clock: Clock,
) : BomService {
    @Transactional(readOnly = true)
    override fun usages(
        actor: Actor,
        parentId: MasterId,
        iterationId: UUID?,
        workingCopy: Boolean,
    ): List<UsageLine> {
        val parent = versioning.get(actor, parentId)
        val iteration =
            when {
                workingCopy -> versioning.workingCopy(actor, parentId).id.value
                iterationId != null ->
                    versioning
                        .history(actor, parentId)
                        .firstOrNull { it.id.value == iterationId }
                        ?.id
                        ?.value
                        ?: throw NotFoundException(
                            "ITERATION_NOT_FOUND",
                            "There's no such iteration of ${parent.number}",
                        )
                else -> parent.latest.id.value
            }
        return lines(actor, repository.links(iteration))
    }

    /** Lines with their children as [actor] sees them (latest iterations, roadmap 4.2 adds others). */
    fun lines(
        actor: Actor,
        links: List<LinkRow>,
    ): List<UsageLine> {
        if (links.isEmpty()) return emptyList()
        val ids = links.map { it.childMasterId }.distinct()
        val readable =
            versioning
                .list(
                    actor,
                    ObjectQuery(ids = ids.map(::MasterId), limit = ids.size.coerceAtMost(500)),
                ).associateBy {
                    it.id.value
                }
        val parents = repository.withChildren(readable.keys)
        return links.map { l -> l.toLine(readable[l.childMasterId], l.childMasterId in parents) }
    }

    private fun LinkRow.toLine(
        child: VersionedObject?,
        hasChildren: Boolean,
    ) = UsageLine(
        id,
        parentIterationId,
        lineNumber,
        findNumber,
        quantity,
        unit,
        notes,
        if (child == null) {
            UsedPart(childMasterId, false, null, null, null, null, null, null, false)
        } else {
            UsedPart(
                childMasterId,
                true,
                child.number,
                child.name,
                child.typeId.value,
                child.latest.id.value,
                child.latest.version,
                child.latest.state,
                hasChildren,
            )
        },
        createdAt,
        createdBy,
    )

    private fun requirePart(
        actor: Actor,
        typeId: TypeId,
        what: String,
    ) {
        val type = types.get(actor, typeId)
        if (type.baseClass != BaseClass.PART) {
            throw ValidationException("NOT_A_PART", "Only parts have structure: $what is a ${type.displayName}")
        }
    }

    private fun units(actor: Actor): Set<String> =
        enumerations
            .list(actor)
            .firstOrNull { it.name == "unitOfUse" }
            ?.values
            ?.filter { it.active }
            ?.map { it.key }
            ?.toSet() ?: setOf("each")

    private fun checkLine(
        actor: Actor,
        quantity: BigDecimal,
        unit: String,
        lineNumber: Int,
        findNumber: String?,
    ) {
        if (quantity.signum() <= 0) throw ValidationException("INVALID_USAGE", "The quantity must be more than 0")
        if (quantity.scale() > 6 || quantity.precision() - quantity.scale() > 12) {
            throw ValidationException("INVALID_USAGE", "The quantity has too many digits")
        }
        if (unit !in units(actor)) throw ValidationException("INVALID_USAGE", "$unit isn't a unit of use")
        if (lineNumber !in 1..999_999) throw ValidationException("INVALID_USAGE", "Line numbers go from 1 to 999999")
        if ((findNumber?.length ?: 0) >
            40
        ) {
            throw ValidationException("INVALID_USAGE", "A find number is at most 40 characters")
        }
    }

    override fun add(
        actor: Actor,
        parentId: MasterId,
        usage: AddUsage,
    ): UsageLine {
        val parent = versioning.get(actor, parentId)
        val wc = versioning.workingCopy(actor, parentId)
        requirePart(actor, parent.typeId, parent.number)
        // The child must be readable, and a part.
        val child = versioning.get(actor, MasterId(usage.childId))
        requirePart(actor, child.typeId, child.number)
        if (repository.reaches(child.id.value, parent.id.value)) {
            throw ConflictException("CYCLE", "${child.number} already uses ${parent.number}: it can't also be under it")
        }
        val existing = repository.links(wc.id.value)
        val line = usage.lineNumber ?: ((existing.maxOfOrNull { it.lineNumber } ?: 0) / 10 + 1) * 10
        if (existing.any {
                it.lineNumber == line
            }
        ) {
            throw ConflictException("DUPLICATE_LINE_NUMBER", "Line $line is already used")
        }
        val unit =
            usage.unit ?: (child.latest.attributes["defaultUnit"] as? String)?.takeIf { it in units(actor) } ?: "each"
        val find = usage.findNumber?.trim()?.ifEmpty { null }
        checkLine(actor, usage.quantity, unit, line, find)
        val row =
            LinkRow(
                UuidV7.generate(clock),
                wc.id.value,
                child.id.value,
                line,
                find,
                usage.quantity,
                unit,
                usage.notes
                    ?.trim()
                    ?.ifEmpty { null }
                    ?.take(2000),
                clock.instant(),
                actor.principal.value,
            )
        repository.insert(actor.tenant, row)
        events.publish(
            actor,
            StructureChanged(
                parent.id.value,
                wc.id.value,
                "ADDED",
                line,
                child.id.value,
                child.number,
                usage.quantity,
                unit,
            ),
        )
        return lines(actor, listOf(repository.link(row.id)!!)).single()
    }

    private fun own(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
    ): Pair<UUID, LinkRow> {
        val wc = versioning.workingCopy(actor, parentId)
        val row = repository.link(usageId)
        if (row == null || row.parentIterationId != wc.id.value) {
            throw NotFoundException("USAGE_NOT_FOUND", "There's no such line in your working copy")
        }
        return wc.id.value to row
    }

    override fun update(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
        usage: UpdateUsage,
    ): UsageLine {
        val (wc, row) = own(actor, parentId, usageId)
        val find = usage.findNumber?.trim()?.ifEmpty { null }
        checkLine(actor, usage.quantity, usage.unit, usage.lineNumber, find)
        if (usage.lineNumber != row.lineNumber && repository.links(wc).any { it.lineNumber == usage.lineNumber }) {
            throw ConflictException("DUPLICATE_LINE_NUMBER", "Line ${usage.lineNumber} is already used")
        }
        val updated =
            row.copy(
                lineNumber = usage.lineNumber,
                findNumber = find,
                quantity = usage.quantity,
                unit = usage.unit,
                notes =
                    usage.notes
                        ?.trim()
                        ?.ifEmpty { null }
                        ?.take(2000),
            )
        repository.update(updated)
        // Not get(): a failed read would mark the transaction for rollback.
        val number =
            versioning
                .list(
                    actor,
                    ObjectQuery(ids = listOf(MasterId(row.childMasterId)), limit = 1),
                ).firstOrNull()
                ?.number
        events.publish(
            actor,
            StructureChanged(
                parentId.value,
                wc,
                "UPDATED",
                usage.lineNumber,
                row.childMasterId,
                number,
                usage.quantity,
                usage.unit,
            ),
        )
        return lines(actor, listOf(repository.link(usageId)!!)).single()
    }

    override fun remove(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
    ) {
        val (wc, row) = own(actor, parentId, usageId)
        repository.delete(usageId)
        events.publish(
            actor,
            StructureChanged(parentId.value, wc, "REMOVED", row.lineNumber, row.childMasterId, null, null, null),
        )
    }
}
