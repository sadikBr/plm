package com.plm.types.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.LayoutChanged
import com.plm.types.api.LayoutDefinition
import com.plm.types.api.LayoutGroup
import com.plm.types.api.LayoutScreen
import com.plm.types.api.LayoutService
import com.plm.types.api.ResolvedLayout
import com.plm.types.api.SaveLayout
import com.plm.types.api.TypeId
import com.plm.types.infra.AttributeRepository
import com.plm.types.infra.LayoutRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
@Transactional
internal class DefaultLayoutService(
    private val layouts: LayoutRepository,
    private val attributes: AttributeRepository,
    private val events: DomainEvents,
    private val clock: Clock,
) : LayoutService {
    @Transactional(readOnly = true)
    override fun resolve(
        actor: Actor,
        typeId: TypeId,
        screen: LayoutScreen,
    ): ResolvedLayout {
        val lineage = lineage(actor, typeId)
        return Layouts.resolve(typeId, screen, lineage, layouts.layouts(actor.tenant, lineage), definitions(lineage))
    }

    @Transactional(readOnly = true)
    override fun own(
        actor: Actor,
        typeId: TypeId,
    ): List<LayoutDefinition> {
        lineage(actor, typeId)
        return layouts.layouts(actor.tenant, listOf(typeId)).sortedBy { it.screen }
    }

    override fun save(
        actor: Actor,
        typeId: TypeId,
        screen: LayoutScreen,
        layout: SaveLayout,
    ): LayoutDefinition {
        requireAdmin(actor)
        val lineage = lineage(actor, typeId)
        val groups =
            layout.groups.map { g ->
                LayoutGroup(
                    key = g.key.trim(),
                    labels = g.labels.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
                    columns = g.columns,
                    attributes = g.attributes.map { it.trim() },
                )
            }
        val hidden = layout.hidden.map { it.trim() }.distinct()
        val errors = Layouts.errors(screen, groups, hidden, definitions(lineage))
        if (errors.isNotEmpty()) {
            throw ValidationException("INVALID_LAYOUT", errors.joinToString("; "), mapOf("errors" to errors))
        }
        val saved = LayoutDefinition(typeId, screen, groups, hidden, clock.instant().truncatedTo(ChronoUnit.MICROS))
        layouts.upsert(actor.tenant, saved)
        events.publish(
            actor,
            LayoutChanged(
                typeId.value,
                screen.name,
                groups.associate { it.key to it.attributes },
                hidden,
                removed = false,
            ),
        )
        return saved
    }

    override fun remove(
        actor: Actor,
        typeId: TypeId,
        screen: LayoutScreen,
    ) {
        requireAdmin(actor)
        lineage(actor, typeId)
        if (!layouts.delete(actor.tenant, typeId, screen)) {
            throw NotFoundException(
                "LAYOUT_NOT_FOUND",
                "Type $typeId has no layout of its own for $screen",
                mapOf("typeId" to typeId.value, "screen" to screen.name),
            )
        }
        events.publish(actor, LayoutChanged(typeId.value, screen.name, emptyMap(), emptyList(), removed = true))
    }

    /** Effective attributes in default order: ancestors' first, then by sort order and name. */
    private fun definitions(lineage: List<TypeId>): List<AttributeDefinition> {
        val depth = lineage.withIndex().associate { (i, t) -> t to i }
        return attributes
            .definitionsOf(lineage)
            .sortedWith(compareBy({ depth.getValue(it.typeId) }, { it.sortOrder }, { it.name }))
    }

    private fun lineage(
        actor: Actor,
        typeId: TypeId,
    ) = attributes.lineage(actor.tenant, typeId).ifEmpty {
        throw NotFoundException("TYPE_NOT_FOUND", "Type $typeId does not exist", mapOf("typeId" to typeId.value))
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators can change layouts")
        }
    }
}
