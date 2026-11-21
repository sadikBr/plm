package com.plm.classification.api

import com.plm.platform.actor.Actor
import java.util.UUID

/** A filter on a class attribute: a range for numbers, a value for the rest. */
data class ClassFilter(
    val key: String,
    val min: java.math.BigDecimal? = null,
    val max: java.math.BigDecimal? = null,
    /** For text, yes-or-no and value-list attributes (text: contains, ignoring case). */
    val value: String? = null,
)

data class ClassifiedPart(
    val id: UUID,
    val number: String,
    val name: String,
    val version: String,
    val state: String,
    val classId: UUID,
    val className: String,
    val values: Map<String, Any?>,
)

data class ClassSearchResult(
    val classId: UUID,
    /** The attributes of the class searched (inherited ones first): the result's columns. */
    val attributes: List<ClassAttribute>,
    val parts: List<ClassifiedPart>,
    val truncated: Boolean,
)

/** How close one value is to the part's: 1 for the same, 0 for far apart or missing. */
data class AttributeMatch(
    val key: String,
    val score: Double,
)

data class SimilarPart(
    val part: ClassifiedPart,
    /** 0 to 1: the mean of the attribute matches. */
    val score: Double,
    val matches: List<AttributeMatch>,
)

data class SimilarParts(
    val partId: UUID,
    val classId: UUID?,
    val attributes: List<ClassAttribute>,
    val values: Map<String, Any?>,
    val similar: List<SimilarPart>,
)

/**
 * Classification search (roadmap 7.12): the parts (latest versions, those the actor can read)
 * classified in a class or below it, filtered by attribute ranges and values; and, from a part,
 * the others of its class ranked by how close their values are. Numbers score by relative
 * difference (1 equal, 0 at twice or more apart), other values by equality; values the part has
 * and the other lacks score 0. Errors: `CLASS_NOT_FOUND`, `INVALID_FILTER`.
 */
interface ClassSearchService {
    fun search(
        actor: Actor,
        classId: UUID,
        filters: List<ClassFilter> = emptyList(),
        limit: Int = 200,
    ): ClassSearchResult

    fun similar(
        actor: Actor,
        partId: UUID,
        limit: Int = 10,
    ): SimilarParts
}
