package com.plm.types.api

import com.plm.platform.actor.Actor
import java.time.Instant

/** The screens a layout can be defined for. [DEFAULT] applies to every screen without its own layout. */
enum class LayoutScreen {
    DEFAULT,

    /** The form for creating an object. */
    CREATE,

    /** The form for editing a checked-out working copy. */
    EDIT,

    /** The read-only attributes panel of an object. */
    INFO,
}

/** A titled group of attributes, shown in one or two columns. Long text attributes always span the full width. */
data class LayoutGroup(
    /** camelCase, unique in the layout. `more` is reserved for attributes the layout doesn't place. */
    val key: String,
    val labels: Labels,
    val columns: Int,
    /** Attribute names, in display order. */
    val attributes: List<String>,
)

/** A type's own layout for one screen, as an administrator saved it. */
data class LayoutDefinition(
    val typeId: TypeId,
    val screen: LayoutScreen,
    val groups: List<LayoutGroup>,
    /** Attributes not shown on this screen. Required attributes without a default can't be hidden on forms. */
    val hidden: List<String>,
    val updatedAt: Instant,
)

/** Where a resolved layout came from: a type (itself or an ancestor) and the screen it was saved for. */
data class LayoutSource(
    val typeId: TypeId,
    val screen: LayoutScreen,
)

/**
 * The layout to render for a type and screen. Contains only active, visible attributes. Attributes
 * the layout doesn't mention (for example ones added later, or defined on a subtype) are appended in
 * a trailing `more` group, so they never silently disappear.
 */
data class ResolvedLayout(
    val typeId: TypeId,
    val screen: LayoutScreen,
    /** Null when no type in the lineage has a layout: one generated group with every attribute. */
    val source: LayoutSource?,
    val groups: List<LayoutGroup>,
)

data class SaveLayout(
    val groups: List<LayoutGroup>,
    val hidden: List<String> = emptyList(),
)

/**
 * Layouts per type and screen (roadmap 1.7): which attributes appear, in which groups and order.
 * Resolution walks from the type up to the root; at each type the screen's own layout wins over
 * that type's [LayoutScreen.DEFAULT]. Reading is open to everyone; saving is for site administrators.
 *
 * Error codes: `ADMIN_REQUIRED`, `TYPE_NOT_FOUND`, `LAYOUT_NOT_FOUND`, `INVALID_LAYOUT`.
 */
interface LayoutService {
    fun resolve(
        actor: Actor,
        typeId: TypeId,
        screen: LayoutScreen,
    ): ResolvedLayout

    /** The layouts saved on [typeId] itself (not inherited ones). */
    fun own(
        actor: Actor,
        typeId: TypeId,
    ): List<LayoutDefinition>

    fun save(
        actor: Actor,
        typeId: TypeId,
        screen: LayoutScreen,
        layout: SaveLayout,
    ): LayoutDefinition

    /** Removes the type's own layout for [screen], so it inherits again. */
    fun remove(
        actor: Actor,
        typeId: TypeId,
        screen: LayoutScreen,
    )
}
