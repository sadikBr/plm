package com.plm.connectors.api

import com.plm.cad.api.CadKind
import com.plm.cad.api.DependencyKind
import com.plm.platform.actor.Actor
import java.util.UUID

/** A dependency of a saved model: another model of the same save (by [key]) or one already in the system (by [id]). */
data class ManifestDependency(
    val key: String? = null,
    val id: UUID? = null,
    val kind: DependencyKind = DependencyKind.COMPONENT,
    val required: Boolean = true,
    /** The instance's name in the assembly, e.g. `Bolt001`. */
    val occurrence: String? = null,
    /** A column-major 4×4 matrix placing it in its parent (millimetres). */
    val transform: List<Double>? = null,
)

/** The part a model represents: an existing one (by [id] or [number]) or a new one ([create]). */
data class ManifestPart(
    val id: UUID? = null,
    val number: String? = null,
    val create: Boolean = false,
    val name: String? = null,
)

/** One file the authoring tool saved. */
data class ManifestModel(
    /** The connector's own name for it (usually its path): what dependencies refer to. */
    val key: String,
    /** The model in the system, when it already is one (the connector stored its id in the file). */
    val id: UUID? = null,
    val number: String? = null,
    val name: String,
    val kind: CadKind,
    /** The file, sent to the vault beforehand (POST /vault/uploads); null keeps the current file. */
    val uploadId: UUID? = null,
    /** Values for the model's attributes (only those its type has). */
    val attributes: Map<String, Any?> = emptyMap(),
    val dependencies: List<ManifestDependency> = emptyList(),
    val part: ManifestPart? = null,
)

/** What a connector saves in one go (roadmap 8.5). */
data class SaveManifest(
    /** Where new models go. */
    val contextId: UUID,
    val folderId: UUID? = null,
    /** e.g. `FreeCAD 1.0`: recorded as the models' authoring application. */
    val application: String,
    val models: List<ManifestModel>,
    /** Check everything in at the end (else it stays checked out to the person). */
    val checkIn: Boolean = true,
    val comment: String? = null,
)

data class SavedModel(
    val key: String,
    val id: UUID,
    val number: String,
    val version: String,
    val created: Boolean,
    val partId: UUID? = null,
    val partNumber: String? = null,
)

/** A file to bring down for a model or one of its dependencies. */
data class ModelFile(
    val id: UUID,
    val number: String,
    val name: String,
    val kind: CadKind?,
    val version: String,
    val iterationId: UUID,
    val fileName: String?,
    val sha256: String?,
    val url: String?,
    /** What it depends on: ids of other entries, with the instance names and placements. */
    val dependencies: List<ManifestDependency>,
)

/**
 * The connector protocol (roadmap 8.5): what an authoring-tool connector (FreeCAD, KiCad,
 * SolidWorks…) calls. [save] creates or updates the models of a manifest, their files,
 * dependencies and parts in one transaction: all of them, or none (the error names the model).
 * [files] lists what to download to open a model with everything it depends on.
 * Errors: `INVALID_MANIFEST`, `UNKNOWN_DEPENDENCY`, `PART_NOT_FOUND`, plus those of versioning,
 * content and CAD, prefixed by the model's key.
 */
interface ConnectorService {
    fun save(
        actor: Actor,
        manifest: SaveManifest,
    ): List<SavedModel>

    fun files(
        actor: Actor,
        id: UUID,
    ): List<ModelFile>
}
