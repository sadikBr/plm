package com.plm.variants.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** A part as generation made, reused or used it. */
data class GeneratedPart(
    val id: UUID,
    val number: String,
    val name: String,
    /** The part it's a variant of (itself, for parts used as they are). */
    val baseId: UUID,
    val baseNumber: String,
    /** CREATED, REUSED, or AS_IS (nothing in it depends on the choices). */
    val outcome: String,
)

data class GenerationResult(
    val top: GeneratedPart,
    val parts: List<GeneratedPart>,
    val created: Int,
    val reused: Int,
)

/** Where a generated variant came from (roadmap 7.9). */
data class VariantOrigin(
    val variantId: UUID,
    val baseId: UUID,
    val baseNumber: String?,
    val baseName: String?,
    val baseVersion: String?,
    val selection: Map<String, String>,
    val specId: UUID?,
    val specName: String?,
    val createdAt: Instant,
    val createdBy: String,
)

/**
 * Variant generation (roadmap 7.9): makes the parts a variant specification builds, level by
 * level. A part whose structure has no conditions anywhere below is used as it is. Any other is
 * made into a variant part (same type and context, named after its base and the choices it
 * depends on), with a structure of its applying lines pointing at the children's variants;
 * but a variant generated earlier from the same iteration of the same part, for the same choices
 * of the options its structure depends on, is reused instead. Needs CREATE where the parts live.
 */
interface VariantGenerationService {
    fun generate(
        actor: Actor,
        specId: UUID,
    ): GenerationResult

    /** Null when the part wasn't generated. */
    fun origin(
        actor: Actor,
        partId: UUID,
    ): VariantOrigin?

    /** Variants generated from [baseId]. */
    fun variantsOf(
        actor: Actor,
        baseId: UUID,
    ): List<VariantOrigin>
}
