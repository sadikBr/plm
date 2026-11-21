package com.plm.bom.api

import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import java.math.BigDecimal

/** One line of a roll-up (roadmap 4.11). */
data class RollupNode(
    val path: String,
    val level: Int,
    val part: UsedPart,
    /** The line's quantity times the quantities above it. */
    val extendedQuantity: BigDecimal,
    /** The part's own value, in [RollupResult.unit]; null when not set. */
    val ownValue: BigDecimal?,
    /** For one piece: its own value, or (for an assembly) its own value plus its lines' totals. */
    val rolledValue: BigDecimal?,
    /** [rolledValue] times the line's quantity. */
    val lineTotal: BigDecimal?,
    /** A part with no lines under it and no value: the total is incomplete. */
    val missing: Boolean,
)

data class RollupResult(
    val attribute: String,
    /** The unit totals are in: a quantity's base unit (kg for mass), null for plain numbers. */
    val unit: String?,
    /** For one piece of the top. */
    val total: BigDecimal,
    /** How many parts lack a value. */
    val missing: Int,
    val nodes: List<RollupNode>,
)

/** A part in a flattened structure: how many in all, across every line and level (roadmap 4.11). */
data class FlatLine(
    val part: UsedPart,
    val unit: String,
    val totalQuantity: BigDecimal,
    /** How many lines use it. */
    val lines: Int,
    val levels: List<Int>,
)

/** Numeric attributes a roll-up can sum, for a part type. */
data class RollupAttribute(
    val name: String,
    val labels: Map<String, String>,
    val dataType: String,
    val dimension: String?,
)

/**
 * Roll-ups and flattened structures (roadmap 4.11). A roll-up sums a numeric attribute (a number,
 * or a quantity converted to its dimension's base unit) up the tree for one piece of the top: an
 * assembly counts its own value plus its lines' quantities times their values.
 */
interface RollupService {
    fun attributes(
        actor: Actor,
        partId: MasterId,
    ): List<RollupAttribute>

    fun rollup(
        actor: Actor,
        partId: MasterId,
        attribute: String,
        spec: ConfigSpec = ConfigSpec.LATEST,
    ): RollupResult

    fun flatten(
        actor: Actor,
        partId: MasterId,
        spec: ConfigSpec = ConfigSpec.LATEST,
    ): List<FlatLine>
}
