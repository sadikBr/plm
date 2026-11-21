package com.plm.classification.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

enum class ClassDataType { NUMBER, TEXT, BOOLEAN, ENUM }

data class ClassAttribute(
    val id: UUID,
    /** Where it's defined: this class or an ancestor. */
    val classId: UUID,
    val key: String,
    val label: String,
    val dataType: ClassDataType,
    val unit: String?,
    /** ENUM: the values it may take. */
    val allowed: List<String>,
    val required: Boolean,
    val position: Int,
)

/** A class of the tree, with its own attributes (not the inherited ones). */
data class ClassNode(
    val id: UUID,
    val parentId: UUID?,
    val code: String,
    val name: String,
    val description: String?,
    val position: Int,
    val attributes: List<ClassAttribute>,
    /** Parts classified here (latest versions), not counting subclasses. */
    val parts: Int,
)

data class SaveClass(
    val code: String,
    val name: String,
    val description: String? = null,
    val parentId: UUID? = null,
)

data class SaveClassAttribute(
    val key: String,
    val label: String,
    val dataType: ClassDataType,
    val unit: String? = null,
    val allowed: List<String> = emptyList(),
    val required: Boolean = false,
)

/** A part's class and its values, at one iteration. */
data class PartClassification(
    val partId: UUID,
    val version: String,
    /** From the root down to the part's class; empty when unclassified. */
    val path: List<ClassNode>,
    /** Every attribute that applies, inherited ones first. */
    val attributes: List<ClassAttribute>,
    val values: Map<String, Any?>,
    val updatedAt: Instant?,
    val updatedBy: String?,
)

/**
 * Classification (roadmap 7.11). Everyone signed in reads the class tree; site administrators
 * change it. A class's attributes apply to it and every class below. A part is classified, with
 * values checked against the attributes (types, value lists, required), on the working copy;
 * check-out and revise carry them forward, so each version keeps its own. Errors:
 * `INVALID_CLASS`, `CLASS_NOT_FOUND`, `CLASS_IN_USE`, `INVALID_CLASS_VALUES`, `NOT_CHECKED_OUT`.
 */
interface ClassificationService {
    fun tree(actor: Actor): List<ClassNode>

    fun createClass(
        actor: Actor,
        request: SaveClass,
    ): ClassNode

    fun updateClass(
        actor: Actor,
        classId: UUID,
        request: SaveClass,
    ): ClassNode

    fun deleteClass(
        actor: Actor,
        classId: UUID,
    )

    fun addAttribute(
        actor: Actor,
        classId: UUID,
        request: SaveClassAttribute,
    ): ClassNode

    fun removeAttribute(
        actor: Actor,
        attributeId: UUID,
    ): ClassNode

    /** The attributes that apply in [classId]: its ancestors' first. */
    fun attributes(
        actor: Actor,
        classId: UUID,
    ): List<ClassAttribute>

    fun classification(
        actor: Actor,
        partId: UUID,
        workingCopy: Boolean = false,
    ): PartClassification

    /** Classifies your working copy of the part in [classId] (null: unclassified) with [values]. */
    fun classify(
        actor: Actor,
        partId: UUID,
        classId: UUID?,
        values: Map<String, Any?>,
    ): PartClassification
}
