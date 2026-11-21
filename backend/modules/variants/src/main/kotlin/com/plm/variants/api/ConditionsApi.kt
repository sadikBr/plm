package com.plm.variants.api

import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.StructureNode
import com.plm.platform.actor.Actor
import java.util.UUID

/** A line's condition (roadmap 7.7), e.g. `MOTOR=HT and not COLOR in (RED, BLUE)`. */
data class LineCondition(
    val lineId: UUID,
    val expression: String,
    val options: List<String>,
)

/** A line of a 150% structure: its condition, and whether it applies to the selection. */
data class ConfiguredLine(
    val node: StructureNode,
    val condition: String?,
    /** Its condition holds, and so do those of the lines above it. */
    val applies: Boolean,
)

data class ConfiguredStructure(
    val rootId: UUID,
    val rootVersion: String?,
    val optionSet: OptionSet?,
    val selection: Map<String, String>,
    /** What the root's rules say about the selection (none when it isn't configurable). */
    val check: SelectionCheck?,
    val lines: List<ConfiguredLine>,
    /** How many lines apply, of all. */
    val applying: Int,
    val total: Int,
)

/**
 * 150% structures (roadmap 7.7): lines of a configurable part carry a condition over its option
 * set's choices, set on the working copy and carried forward with the line. The configured
 * structure is the whole structure ([spec], all levels) with each line's condition and whether
 * it applies to a selection: lines without a condition always do, and a line applies only if
 * the lines above it do. Errors: `INVALID_CONDITION`, `NOT_CONFIGURABLE`, `NOT_CHECKED_OUT`.
 */
interface ConditionService {
    fun conditions(
        actor: Actor,
        partId: UUID,
        workingCopy: Boolean = false,
    ): List<LineCondition>

    /** The conditions of these lines (of parts the actor can read). */
    fun forLines(
        actor: Actor,
        lineIds: List<UUID>,
    ): List<LineCondition>

    /** Sets (or, with a blank [expression], clears) the condition of a line of your working copy. */
    fun setCondition(
        actor: Actor,
        partId: UUID,
        lineId: UUID,
        expression: String?,
    ): LineCondition?

    fun configured(
        actor: Actor,
        partId: UUID,
        selection: Map<String, String>,
        spec: ConfigSpec = ConfigSpec.LATEST,
    ): ConfiguredStructure
}
