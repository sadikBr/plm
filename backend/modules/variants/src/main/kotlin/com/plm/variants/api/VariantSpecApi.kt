package com.plm.variants.api

import com.plm.bom.api.ConfigSpec
import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** A named selection of choices for a configurable product (roadmap 7.8). */
data class VariantSpec(
    val id: UUID,
    val productId: UUID,
    val name: String,
    val description: String?,
    /** Option code to choice code. */
    val selection: Map<String, String>,
    /** How its structure is read: latest, released, as effective for a unit... */
    val spec: ConfigSpec,
    /** What the product's rules say about it now (they may have changed since it was saved). */
    val check: SelectionCheck,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class SaveVariantSpec(
    val name: String,
    val description: String? = null,
    val selection: Map<String, String>,
    val spec: ConfigSpec = ConfigSpec.LATEST,
)

/**
 * Variant specifications (roadmap 7.8): reading needs read access to the product, saving needs
 * MODIFY on it. A specification is saved only when its selection keeps the product's rules
 * (`INVALID_SELECTION`, with the problems); names are unique per product. Its structure is the
 * product's configured structure for the selection, read with its configuration specification.
 */
interface VariantSpecService {
    fun list(
        actor: Actor,
        productId: UUID,
    ): List<VariantSpec>

    fun get(
        actor: Actor,
        specId: UUID,
    ): VariantSpec

    fun create(
        actor: Actor,
        productId: UUID,
        request: SaveVariantSpec,
    ): VariantSpec

    fun update(
        actor: Actor,
        specId: UUID,
        request: SaveVariantSpec,
    ): VariantSpec

    fun delete(
        actor: Actor,
        specId: UUID,
    )

    fun structure(
        actor: Actor,
        specId: UUID,
    ): ConfiguredStructure
}
