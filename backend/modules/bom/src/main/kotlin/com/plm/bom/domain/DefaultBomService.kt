package com.plm.bom.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.Permission
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ConfigSpecType
import com.plm.bom.api.StructureChanged
import com.plm.bom.api.StructureNode
import com.plm.bom.api.StructureResult
import com.plm.bom.api.UpdateUsage
import com.plm.bom.api.UsageLine
import com.plm.bom.api.UsedPart
import com.plm.bom.api.WhereUsedNode
import com.plm.bom.api.WhereUsedResult
import com.plm.bom.infra.BomRepository
import com.plm.bom.infra.LinkRow
import com.plm.bom.infra.Resolved
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
    private val access: AccessService,
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

    private fun readable(actor: Actor) =
        access.filter(actor, Permission.READ, AccessColumns("m.context_id", "m.type_id", "li.state", "m.id"))

    /** Lines with their children as [actor] sees them, at the iterations [spec] picks. */
    fun lines(
        actor: Actor,
        links: List<LinkRow>,
        spec: ConfigSpec = ConfigSpec.LATEST,
    ): List<UsageLine> {
        if (links.isEmpty()) return emptyList()
        val resolved = repository.resolve(links.map { it.childMasterId }.distinct(), spec, readable(actor))
        val designators = repository.designators(links.map { it.id })
        return links.map { it.toLine(resolved[it.childMasterId], designators[it.id].orEmpty()) }
    }

    private fun LinkRow.toLine(
        child: Resolved?,
        designators: List<String>,
    ) = UsageLine(
        id,
        parentIterationId,
        lineNumber,
        findNumber,
        quantity,
        unit,
        notes,
        designators,
        if (child == null) {
            UsedPart(childMasterId, false, null, null, null, null, null, null, false)
        } else {
            UsedPart(
                childMasterId,
                true,
                child.number,
                child.name,
                child.typeId,
                child.iterationId,
                child.version,
                child.state,
                child.hasChildren,
            )
        },
        createdAt,
        createdBy,
    )

    @Transactional(readOnly = true)
    override fun structure(
        actor: Actor,
        rootId: MasterId,
        spec: ConfigSpec,
        depth: Int,
        workingCopy: Boolean,
        limit: Int,
    ): StructureResult {
        val started = System.nanoTime()
        if (spec.type == ConfigSpecType.AS_OF &&
            spec.asOf == null
        ) {
            throw ValidationException("INVALID_SPEC", "Choose a date")
        }
        val root = versioning.get(actor, rootId)
        val (rootIteration, rootVersion) =
            if (workingCopy) {
                versioning.workingCopy(actor, rootId).let { it.id.value to it.version }
            } else {
                repository.resolve(listOf(root.id.value), spec, readable(actor))[root.id.value].let {
                    it?.iterationId to
                        it?.version
                }
            }
        val nodes = ArrayList<StructureNode>()
        var truncated = false
        // Breadth first, one level at a time: a query for the lines of the level, one for their children.
        var frontier: List<Pair<UUID, String?>> = listOfNotNull(rootIteration?.let { it to null })
        var level = 1
        val maxLevel = depth.coerceIn(1, 50)
        while (frontier.isNotEmpty() && level <= maxLevel && !truncated) {
            val links = repository.linksOf(frontier.map { it.first }.distinct())
            val children =
                repository.resolve(
                    links.values
                        .flatten()
                        .map { it.childMasterId }
                        .distinct(),
                    spec,
                    readable(actor),
                )
            val designatorsOf = repository.designators(links.values.flatten().map { it.id })
            val next = ArrayList<Pair<UUID, String?>>()
            for ((iteration, parentPath) in frontier) {
                for (link in links[iteration].orEmpty()) {
                    if (nodes.size >= limit) {
                        truncated = true
                        break
                    }
                    val path = if (parentPath == null) "${link.lineNumber}" else "$parentPath.${link.lineNumber}"
                    val child = children[link.childMasterId]
                    nodes +=
                        StructureNode(level, path, parentPath, link.toLine(child, designatorsOf[link.id].orEmpty()))
                    if (child?.iterationId != null && child.hasChildren) next += child.iterationId to path
                }
            }
            frontier = next
            level++
        }
        return StructureResult(
            root.id.value,
            rootIteration,
            rootVersion,
            spec,
            // Depth first, as a tree table shows it.
            treeOrder(nodes),
            truncated,
            (System.nanoTime() - started) / 1_000_000,
        )
    }

    @Transactional(readOnly = true)
    override fun whereUsed(
        actor: Actor,
        childId: MasterId,
        spec: ConfigSpec,
        depth: Int,
        limit: Int,
    ): WhereUsedResult {
        val child = versioning.get(actor, childId)
        val nodes = ArrayList<WhereUsedNode>()
        var truncated = false
        // Upwards, a level at a time: the lines using the frontier's parts, kept only where the
        // using part's iteration under the specification is the one the line is on.
        var frontier: List<Pair<UUID, String?>> = listOf(child.id.value to null)
        var level = 1
        while (frontier.isNotEmpty() && level <= depth.coerceIn(1, 50) && !truncated) {
            val using = repository.usingLines(frontier.map { it.first }.distinct())
            val parents = repository.resolve(using.map { it.second }.distinct(), spec, readable(actor))
            val unreadable = using.map { it.second }.filter { it !in parents }.toSet()
            val byChild = using.groupBy { it.first.childMasterId }
            val next = ArrayList<Pair<UUID, String?>>()
            for ((id, path) in frontier) {
                val lines =
                    byChild[id].orEmpty().filter { (link, parent) ->
                        parent in unreadable || parents[parent]?.iterationId == link.parentIterationId
                    }
                // A part nobody may read is shown once per child, without details.
                for ((link, parent) in lines.distinctBy { if (it.second in unreadable) it.second else it.first.id }) {
                    if (nodes.size >= limit) {
                        truncated = true
                        break
                    }
                    val p = parents[parent]
                    val here = if (path == null) "$parent" else "$path/$parent"
                    nodes +=
                        WhereUsedNode(
                            level,
                            here,
                            path,
                            if (p == null) {
                                UsedPart(parent, false, null, null, null, null, null, null, false)
                            } else {
                                UsedPart(
                                    parent,
                                    true,
                                    p.number,
                                    p.name,
                                    p.typeId,
                                    p.iterationId,
                                    p.version,
                                    p.state,
                                    true,
                                )
                            },
                            link.lineNumber,
                            link.findNumber,
                            link.quantity,
                            link.unit,
                            false,
                        )
                    if (p != null) next += parent to here
                }
            }
            frontier = next
            level++
        }
        // Tops: lines nothing above them in the result (and, within the depth, nothing uses).
        val withParents = nodes.mapNotNull { it.parentPath }.toSet()
        val atDepthLimit = level > depth.coerceIn(1, 50)
        val marked =
            nodes.map { n ->
                val top = n.path !in withParents && n.parent.readable && (!atDepthLimit || n.level < depth)
                n.copy(top = top)
            }
        return WhereUsedResult(child.id.value, spec, whereUsedOrder(marked), truncated)
    }

    private fun whereUsedOrder(nodes: List<WhereUsedNode>): List<WhereUsedNode> {
        val byParent = nodes.groupBy { it.parentPath }
        val out = ArrayList<WhereUsedNode>(nodes.size)
        val stack = ArrayDeque<WhereUsedNode>()
        byParent[null].orEmpty().sortedByDescending { it.parent.number }.forEach(stack::addLast)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out += n
            byParent[n.path].orEmpty().sortedByDescending { it.parent.number }.forEach(stack::addLast)
        }
        return out
    }

    private fun treeOrder(nodes: List<StructureNode>): List<StructureNode> {
        val byParent = nodes.groupBy { it.parentPath }
        val out = ArrayList<StructureNode>(nodes.size)
        val stack = ArrayDeque<StructureNode>()
        byParent[null].orEmpty().asReversed().forEach(stack::addLast)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out += n
            byParent[n.path].orEmpty().asReversed().forEach(stack::addLast)
        }
        return out
    }

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

    /** Designators match the quantity: one per piece, counted in units of each. */
    private fun designators(
        text: String?,
        quantity: BigDecimal,
        unit: String,
        others: Set<String>,
    ): List<String> {
        val list = Designators.parse(text)
        if (list.isEmpty()) return list
        if (unit != "each" || quantity.stripTrailingZeros().scale() > 0) {
            throw ValidationException(
                "INVALID_DESIGNATORS",
                "Reference designators need a whole quantity of single pieces (unit: each)",
            )
        }
        if (BigDecimal(list.size).compareTo(quantity) != 0) {
            throw ValidationException(
                "INVALID_DESIGNATORS",
                "${list.size} designators (${Designators.format(list)}) for a quantity of ${quantity.toPlainString()}",
            )
        }
        val clash = list.filter { it in others }
        if (clash.isNotEmpty()) {
            throw ValidationException(
                "INVALID_DESIGNATORS",
                "Already used on another line: ${Designators.format(clash)}",
            )
        }
        return list
    }

    /** Designators of the working copy's other lines. */
    private fun othersDesignators(
        wc: UUID,
        except: UUID?,
    ): Set<String> {
        val ids = repository.links(wc).map { it.id }.filter { it != except }
        return repository
            .designators(ids)
            .values
            .flatten()
            .toSet()
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
        val designators = designators(usage.designators, usage.quantity, unit, othersDesignators(wc.id.value, null))
        repository.insert(actor.tenant, row)
        repository.setDesignators(row.id, designators)
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
        val designators = designators(usage.designators, usage.quantity, usage.unit, othersDesignators(wc, usageId))
        repository.update(updated)
        repository.setDesignators(usageId, designators)
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

    override fun replace(
        actor: Actor,
        parentId: MasterId,
        usageId: UUID,
        childId: UUID,
    ): UsageLine {
        val (wc, row) = own(actor, parentId, usageId)
        val parent = versioning.get(actor, parentId)
        val child = versioning.get(actor, MasterId(childId))
        requirePart(actor, child.typeId, child.number)
        if (repository.reaches(child.id.value, parent.id.value)) {
            throw ConflictException("CYCLE", "${child.number} already uses ${parent.number}: it can't also be under it")
        }
        repository.replaceChild(usageId, child.id.value)
        events.publish(
            actor,
            StructureChanged(
                parentId.value,
                wc,
                "REPLACED",
                row.lineNumber,
                child.id.value,
                child.number,
                row.quantity,
                row.unit,
            ),
        )
        return lines(actor, listOf(repository.link(usageId)!!)).single()
    }

    override fun reorder(
        actor: Actor,
        parentId: MasterId,
        usageIds: List<UUID>,
    ): List<UsageLine> {
        val wc = versioning.workingCopy(actor, parentId).id.value
        val existing = repository.links(wc)
        if (usageIds.toSet() != existing.map { it.id }.toSet() || usageIds.size != existing.size) {
            throw ValidationException("INVALID_USAGE", "Give every line of the working copy once")
        }
        usageIds.forEachIndexed { i, id -> repository.renumber(id, (i + 1) * 10) }
        events.publish(actor, StructureChanged(parentId.value, wc, "REORDERED", 0, parentId.value, null, null, null))
        return lines(actor, repository.links(wc))
    }

    override fun paste(
        actor: Actor,
        parentId: MasterId,
        usageIds: List<UUID>,
    ): List<UsageLine> {
        if (usageIds.isEmpty() ||
            usageIds.size > 500
        ) {
            throw ValidationException("INVALID_USAGE", "Paste 1 to 500 lines")
        }
        val parent = versioning.get(actor, parentId)
        val wc = versioning.workingCopy(actor, parentId).id.value
        val sources =
            usageIds.map {
                repository.link(it)
                    ?: throw NotFoundException("USAGE_NOT_FOUND", "There's no such line")
            }
        // Only lines of structures the actor may read, of parts they may read.
        val readableParents = repository.mastersOf(sources.map { it.parentIterationId }.distinct())
        readableParents.values.distinct().forEach { versioning.get(actor, MasterId(it)) }
        val children = sources.map { it.childMasterId }.distinct().associateWith { versioning.get(actor, MasterId(it)) }
        children.values.forEach { c ->
            if (repository.reaches(c.id.value, parent.id.value)) {
                throw ConflictException("CYCLE", "${c.number} already uses ${parent.number}: it can't also be under it")
            }
        }
        var next = ((repository.links(wc).maxOfOrNull { it.lineNumber } ?: 0) / 10 + 1) * 10
        val added =
            sources.map { src ->
                val row =
                    src.copy(
                        id = UuidV7.generate(clock),
                        parentIterationId = wc,
                        lineNumber = next,
                        createdAt = clock.instant(),
                        createdBy = actor.principal.value,
                    )
                next += 10
                repository.insert(actor.tenant, row)
                repository.copyDesignators(src.id, row.id)
                events.publish(
                    actor,
                    StructureChanged(
                        parent.id.value,
                        wc,
                        "ADDED",
                        row.lineNumber,
                        row.childMasterId,
                        children[row.childMasterId]?.number,
                        row.quantity,
                        row.unit,
                    ),
                )
                row
            }
        return lines(actor, added)
    }
}
