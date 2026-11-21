package com.plm.cad.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

enum class PmiKind { DIMENSION, TOLERANCE, DATUM, SURFACE_FINISH, NOTE }

/** An annotation pointing at [at] (model coordinates, its units), its text shown at [label]. */
data class PmiAnnotation(
    val id: String,
    val kind: PmiKind,
    /** As shown, e.g. `Ø22 H7`, `⌖ 0.05 A B`, `A`, `Ra 1.6`. */
    val text: String,
    val at: List<Double>,
    val label: List<Double>? = null,
)

/** Where a view looks from, in model coordinates. */
data class PmiCamera(
    val position: List<Double>,
    val target: List<Double>,
)

/** A named group of annotations, seen from [camera]. */
data class PmiView(
    val id: String,
    val name: String,
    val camera: PmiCamera? = null,
    val annotations: List<PmiAnnotation> = emptyList(),
)

data class ModelPmi(
    val modelId: UUID,
    val version: String,
    val views: List<PmiView>,
    /** The sidecar file it was read from, or null when authored in the viewer. */
    val source: String?,
    val updatedAt: Instant?,
    val updatedBy: String?,
)

/**
 * Model-based definition (roadmap 6.12): PMI on CAD parts and assemblies, kept with each version and
 * carried to the next. The viewer's converter can't read PMI out of STEP files, so it comes from a
 * sidecar `*.pmi.json` file attached to the model (read at check-in when it's new in that iteration)
 * or is authored in the 3D view; either way it's changed on the working copy. Errors: `INVALID_PMI`,
 * `NOT_CHECKED_OUT`, `NOT_A_MODEL`.
 */
interface PmiService {
    fun get(
        actor: Actor,
        modelId: UUID,
        workingCopy: Boolean = false,
    ): ModelPmi

    /** Replaces the working copy's PMI with [views]. */
    fun put(
        actor: Actor,
        modelId: UUID,
        views: List<PmiView>,
        source: String? = null,
    ): ModelPmi
}
