package com.plm.variants.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

data class Choice(
    val id: UUID,
    val code: String,
    val name: String,
    val position: Int,
    val retired: Boolean,
)

data class OptionDef(
    val id: UUID,
    val code: String,
    val name: String,
    /** A variant must choose one of its choices. */
    val required: Boolean,
    val position: Int,
    val choices: List<Choice>,
)

/** Options and their choices (roadmap 7.5), kept in a library or product. */
data class OptionSet(
    val id: UUID,
    val contextId: UUID,
    val name: String,
    val description: String?,
    val options: List<OptionDef>,
    /** How many configurable parts use it. */
    val usedBy: Int,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant,
)

data class SaveOptionSet(
    val name: String,
    val description: String? = null,
)

data class SaveOption(
    val code: String,
    val name: String,
    val required: Boolean = true,
)

data class SaveChoice(
    val code: String,
    val name: String,
    val retired: Boolean = false,
)

/** A part's options: the set it's configured with, if any. */
data class PartOptions(
    val partId: UUID,
    val optionSet: OptionSet?,
)

/**
 * Option sets (roadmap 7.5): reading needs READ in their context, changing needs MODIFY there.
 * Codes are short capitals, digits and `_` (e.g. MOTOR, HT), unique within their set or option,
 * as conditions (7.7) refer to them. Choices are retired rather than deleted. A part becomes
 * configurable when assigned a set (MODIFY on the part). Errors: `INVALID_OPTIONS`,
 * `DUPLICATE_CODE`, `OPTION_SET_NOT_FOUND`, `OPTION_SET_IN_USE`.
 */
interface OptionService {
    fun sets(
        actor: Actor,
        contextId: UUID? = null,
    ): List<OptionSet>

    fun get(
        actor: Actor,
        setId: UUID,
    ): OptionSet

    fun create(
        actor: Actor,
        contextId: UUID,
        request: SaveOptionSet,
    ): OptionSet

    fun update(
        actor: Actor,
        setId: UUID,
        request: SaveOptionSet,
    ): OptionSet

    fun delete(
        actor: Actor,
        setId: UUID,
    )

    fun addOption(
        actor: Actor,
        setId: UUID,
        request: SaveOption,
    ): OptionSet

    fun updateOption(
        actor: Actor,
        optionId: UUID,
        request: SaveOption,
    ): OptionSet

    fun removeOption(
        actor: Actor,
        optionId: UUID,
    ): OptionSet

    fun addChoice(
        actor: Actor,
        optionId: UUID,
        request: SaveChoice,
    ): OptionSet

    fun updateChoice(
        actor: Actor,
        choiceId: UUID,
        request: SaveChoice,
    ): OptionSet

    fun partOptions(
        actor: Actor,
        partId: UUID,
    ): PartOptions

    /** Makes the part configurable with [setId], or plain again with null. */
    fun assign(
        actor: Actor,
        partId: UUID,
        setId: UUID?,
    ): PartOptions
}
