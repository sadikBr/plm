package com.plm.types.infra.web

import com.plm.platform.actor.Actor
import com.plm.types.api.LayoutDefinition
import com.plm.types.api.LayoutGroup
import com.plm.types.api.LayoutScreen
import com.plm.types.api.LayoutService
import com.plm.types.api.ResolvedLayout
import com.plm.types.api.SaveLayout
import com.plm.types.api.TypeId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/types/{typeId}/layouts")
internal class LayoutController(
    private val layouts: LayoutService,
) {
    /** The layouts saved on this type itself. Inherited ones are on the parent types. */
    @GetMapping
    fun own(
        actor: Actor,
        @PathVariable typeId: UUID,
    ) = layouts.own(actor, TypeId(typeId)).map(::LayoutResponse)

    /** The layout to render for a screen, resolved through the type hierarchy. Only active, visible attributes. */
    @GetMapping("/{screen}")
    fun resolve(
        actor: Actor,
        @PathVariable typeId: UUID,
        @PathVariable screen: LayoutScreen,
    ) = ResolvedLayoutResponse(layouts.resolve(actor, TypeId(typeId), screen))

    /** Saves this type's own layout for a screen. Site admins only. */
    @PutMapping("/{screen}")
    fun save(
        actor: Actor,
        @PathVariable typeId: UUID,
        @PathVariable screen: LayoutScreen,
        @RequestBody request: SaveLayoutRequest,
    ) = LayoutResponse(
        layouts.save(
            actor,
            TypeId(typeId),
            screen,
            SaveLayout(request.groups.map { it.toGroup() }, request.hidden.orEmpty()),
        ),
    )

    /** Removes this type's own layout for a screen, so it inherits again. Site admins only. */
    @DeleteMapping("/{screen}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        actor: Actor,
        @PathVariable typeId: UUID,
        @PathVariable screen: LayoutScreen,
    ) = layouts.remove(actor, TypeId(typeId), screen)
}

internal data class LayoutGroupDto(
    val key: String,
    val labels: Map<String, String>,
    /** 1 or 2. Long text attributes span both columns. */
    val columns: Int,
    val attributes: List<String>,
) {
    constructor(g: LayoutGroup) : this(g.key, g.labels, g.columns, g.attributes)

    fun toGroup() = LayoutGroup(key, labels, columns, attributes)
}

internal data class SaveLayoutRequest(
    val groups: List<LayoutGroupDto>,
    val hidden: List<String>? = null,
)

internal data class LayoutResponse(
    val typeId: UUID,
    val screen: LayoutScreen,
    val groups: List<LayoutGroupDto>,
    val hidden: List<String>,
    val updatedAt: Instant,
) {
    constructor(
        l: LayoutDefinition,
    ) : this(l.typeId.value, l.screen, l.groups.map(::LayoutGroupDto), l.hidden, l.updatedAt)
}

internal data class LayoutSourceDto(
    val typeId: UUID,
    val screen: LayoutScreen,
)

internal data class ResolvedLayoutResponse(
    val typeId: UUID,
    val screen: LayoutScreen,
    /** Where the layout comes from; null when generated (no type in the hierarchy has one). */
    val source: LayoutSourceDto?,
    val groups: List<LayoutGroupDto>,
) {
    constructor(r: ResolvedLayout) : this(
        r.typeId.value,
        r.screen,
        r.source?.let { LayoutSourceDto(it.typeId.value, it.screen) },
        r.groups.map(::LayoutGroupDto),
    )
}
