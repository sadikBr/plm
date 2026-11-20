package com.plm.types.domain

import com.plm.types.api.AttributeDefinition
import com.plm.types.api.LayoutDefinition
import com.plm.types.api.LayoutGroup
import com.plm.types.api.LayoutScreen
import com.plm.types.api.LayoutSource
import com.plm.types.api.ResolvedLayout
import com.plm.types.api.TypeId

internal const val MORE_GROUP = "more"
private val GROUP_KEY = Regex("^[a-z][A-Za-z0-9]{0,62}$")
private const val MAX_GROUPS = 50

/** Pure layout logic: resolution along the type lineage, and validation of a layout before it's saved. */
internal object Layouts {
    /**
     * @param lineage the type's lineage, root first (as the repository returns it).
     * @param saved every saved layout on any type of the lineage.
     * @param attributes the type's effective attributes (including inactive), in default order.
     */
    fun resolve(
        typeId: TypeId,
        screen: LayoutScreen,
        lineage: List<TypeId>,
        saved: List<LayoutDefinition>,
        attributes: List<AttributeDefinition>,
    ): ResolvedLayout {
        val byKey = saved.associateBy { it.typeId to it.screen }
        val layout =
            lineage.reversed().firstNotNullOfOrNull { type ->
                byKey[type to screen] ?: byKey[type to LayoutScreen.DEFAULT]
            }
        val visible = attributes.filter { it.active && it.name !in layout?.hidden.orEmpty() }.map { it.name }
        if (layout == null) {
            val all = listOf(LayoutGroup("attributes", mapOf("en" to "Attributes"), 2, visible))
            return ResolvedLayout(typeId, screen, null, all.filter { it.attributes.isNotEmpty() })
        }

        val remaining = visible.toMutableSet()
        val groups =
            layout.groups.map { group ->
                group.copy(attributes = group.attributes.filter { remaining.remove(it) })
            }
        val more = visible.filter { it in remaining }
        val all = groups + LayoutGroup(MORE_GROUP, mapOf("en" to "More attributes"), 2, more)
        return ResolvedLayout(
            typeId,
            screen,
            LayoutSource(layout.typeId, layout.screen),
            all.filter { it.attributes.isNotEmpty() },
        )
    }

    /** Human-readable problems with a layout for a type with these effective [attributes]; empty when valid. */
    fun errors(
        screen: LayoutScreen,
        groups: List<LayoutGroup>,
        hidden: List<String>,
        attributes: List<AttributeDefinition>,
    ): List<String> {
        val errors = mutableListOf<String>()
        val known = attributes.associateBy { it.name }
        if (groups.isEmpty()) errors += "A layout needs at least one group"
        if (groups.size > MAX_GROUPS) errors += "A layout can have at most $MAX_GROUPS groups"

        val keys = mutableSetOf<String>()
        val placed = mutableSetOf<String>()
        for (group in groups) {
            val name = "Group '${group.key}'"
            if (!GROUP_KEY.matches(group.key)) {
                errors +=
                    "$name: key must be camelCase (letters and digits, starting lowercase)"
            }
            if (group.key ==
                MORE_GROUP
            ) {
                errors += "$name: '$MORE_GROUP' is reserved for attributes the layout doesn't place"
            }
            if (!keys.add(group.key)) errors += "$name appears more than once"
            if (group.labels["en"].isNullOrBlank()) errors += "$name: an English label (labels.en) is required"
            if (group.labels.values.any { it.length > 200 }) errors += "$name: labels must be at most 200 characters"
            if (group.columns !in 1..2) errors += "$name: columns must be 1 or 2"
            if (group.attributes.isEmpty()) errors += "$name has no attributes"
            for (attribute in group.attributes) {
                if (attribute !in known) errors += "$name: no attribute '$attribute' on this type"
                if (!placed.add(attribute)) errors += "Attribute '$attribute' is placed more than once"
            }
        }
        for (attribute in hidden.distinct()) {
            val def = known[attribute]
            when {
                def == null -> errors += "Hidden: no attribute '$attribute' on this type"
                attribute in placed -> errors += "Attribute '$attribute' is both placed and hidden"
                screen != LayoutScreen.INFO && def.required && def.defaultValue == null ->
                    errors += "Attribute '$attribute' is required and has no default, so it can't be hidden from forms"
            }
        }
        return errors
    }
}
