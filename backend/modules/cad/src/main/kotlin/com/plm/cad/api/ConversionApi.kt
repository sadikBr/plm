package com.plm.cad.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

enum class ConversionStatus { NONE, QUEUED, RUNNING, READY, FAILED }

/** A CAD document version's viewable (roadmap 6.6). */
data class Viewable(
    val status: ConversionStatus,
    /** The version it is of: the latest's, or for a family instance its generic's. */
    val modelId: UUID,
    val version: String?,
    val fileName: String?,
    /** A short-lived link to the .glb when READY. */
    val url: String?,
    val error: String?,
    val jobId: UUID?,
    val meshes: Int?,
    val triangles: Int?,
    val updatedAt: Instant?,
)

/**
 * Conversion (roadmap 6.6): when a CAD document is checked in with a STEP file, a background job
 * converts it to glTF (the converter in workers/cad-converter); the result is kept with the file,
 * so a version whose file didn't change has its viewable at once. A family instance shows its
 * generic's. Errors: `NO_MODEL_FILE`.
 */
interface ConversionService {
    fun viewable(
        actor: Actor,
        id: UUID,
    ): Viewable

    /** Converts the latest version again (after a failure, or a better converter). */
    fun convert(
        actor: Actor,
        id: UUID,
    ): Viewable
}
