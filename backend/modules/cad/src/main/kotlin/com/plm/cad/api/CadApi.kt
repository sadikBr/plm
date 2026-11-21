package com.plm.cad.api

import com.plm.platform.actor.Actor
import java.util.UUID

/** What a CAD document is (roadmap 6.1). */
enum class CadKind { PART, ASSEMBLY, DRAWING }

/** How a CAD document depends on another. */
enum class DependencyKind {
    /** An assembly's component, placed by its transform. */
    COMPONENT,

    /** A model a drawing shows. */
    DRAWING,

    /** Anything else it refers to (a skeleton, a reference model). */
    REFERENCE,
}

/** The model a dependency points to, as the reader sees it; null fields when they may not read it. */
data class CadModel(
    val id: UUID,
    val readable: Boolean,
    val number: String?,
    val name: String?,
    val typeId: UUID?,
    val kind: CadKind?,
    val version: String?,
    val state: String?,
    /** Whether it depends on others in turn (for expanding). */
    val hasDependencies: Boolean,
)

data class CadDependency(
    val id: UUID,
    val kind: DependencyKind,
    val required: Boolean,
    val occurrence: String?,
    /** Column-major 4x4 placement in the parent, millimetres; null: where it was modelled. */
    val transform: List<Double>?,
    val position: Int,
    val child: CadModel,
)

data class AddDependency(
    val childId: UUID,
    val kind: DependencyKind = DependencyKind.COMPONENT,
    val required: Boolean = true,
    val occurrence: String? = null,
    val transform: List<Double>? = null,
)

data class UpdateDependency(
    val required: Boolean,
    val occurrence: String? = null,
    val transform: List<Double>? = null,
)

/** One line of the dependency tree, [depth] levels under the root. */
data class CadTreeNode(
    val depth: Int,
    /** The dependency that brought it in; null for the root. */
    val dependency: CadDependency?,
    val model: CadModel,
    /** Its placement in the root: the product of the transforms above it. */
    val worldTransform: List<Double>?,
)

data class CadWhereUsed(
    val dependencyId: UUID,
    val kind: DependencyKind,
    val occurrence: String?,
    val parent: CadModel,
    /** The parent's version that uses it. */
    val parentVersion: String,
    /** Whether that is the parent's latest version. */
    val latest: Boolean,
)

/**
 * CAD documents and their dependencies (roadmap 6.1). A CAD document's version depends on other
 * CAD documents: an assembly on its components (one dependency per placement), a drawing on the
 * models it shows. Dependencies are edited on the working copy (`NOT_CHECKED_OUT`), carried forward
 * on check-out and revise, and read at the latest version of each model. Errors: `NOT_A_CAD_DOCUMENT`,
 * `INVALID_DEPENDENCY`, `CYCLE`, `DEPENDENCY_NOT_FOUND`.
 */
interface CadService {
    fun kindOf(
        actor: Actor,
        id: UUID,
    ): CadKind?

    /** The dependencies of [id]'s latest version, or of the caller's working copy. */
    fun dependencies(
        actor: Actor,
        id: UUID,
        workingCopy: Boolean = false,
    ): List<CadDependency>

    /** Everything under [id], [depth] levels deep (1 to 30), with each model's placement in the root. */
    fun tree(
        actor: Actor,
        id: UUID,
        depth: Int = 30,
        workingCopy: Boolean = false,
    ): List<CadTreeNode>

    /** The CAD documents whose versions depend on [id]. */
    fun whereUsed(
        actor: Actor,
        id: UUID,
    ): List<CadWhereUsed>

    fun add(
        actor: Actor,
        parentId: UUID,
        request: AddDependency,
    ): CadDependency

    fun update(
        actor: Actor,
        parentId: UUID,
        dependencyId: UUID,
        request: UpdateDependency,
    ): CadDependency

    fun remove(
        actor: Actor,
        parentId: UUID,
        dependencyId: UUID,
    )
}

/** Matrix helpers for column-major 4x4 transforms. */
object Transforms {
    val IDENTITY: List<Double> = listOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    fun multiply(
        a: List<Double>,
        b: List<Double>,
    ): List<Double> =
        List(16) { i ->
            val col = i / 4
            val row = i % 4
            (0 until 4).sumOf { k -> a[k * 4 + row] * b[col * 4 + k] }
        }

    fun translation(
        x: Double,
        y: Double,
        z: Double,
    ): List<Double> =
        IDENTITY.toMutableList().also {
            it[12] = x
            it[13] = y
            it[14] = z
        }
}
