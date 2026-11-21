package com.plm.classification.infra.web

import com.plm.classification.api.ClassificationService
import com.plm.classification.api.SaveClass
import com.plm.classification.api.SaveClassAttribute
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ClassifyPart(
    /** Null: unclassified. */
    val classId: UUID?,
    val values: Map<String, Any?> = emptyMap(),
)

/** Classification (roadmap 7.11): the class tree, and parts' classes and values. */
@RestController
internal class ClassificationController(
    private val classification: ClassificationService,
) {
    @GetMapping("/api/v1/classes")
    fun tree(actor: Actor) = classification.tree(actor)

    @PostMapping("/api/v1/classes")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: SaveClass,
    ) = classification.createClass(actor, request)

    @PutMapping("/api/v1/classes/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveClass,
    ) = classification.updateClass(actor, id, request)

    @DeleteMapping("/api/v1/classes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = classification.deleteClass(actor, id)

    @GetMapping("/api/v1/classes/{id}/attributes")
    fun attributes(
        actor: Actor,
        @PathVariable id: UUID,
    ) = classification.attributes(actor, id)

    @PostMapping("/api/v1/classes/{id}/attributes")
    fun addAttribute(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveClassAttribute,
    ) = classification.addAttribute(actor, id, request)

    @DeleteMapping("/api/v1/class-attributes/{attributeId}")
    fun removeAttribute(
        actor: Actor,
        @PathVariable attributeId: UUID,
    ) = classification.removeAttribute(actor, attributeId)

    @GetMapping("/api/v1/objects/{id}/classification")
    fun classification(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = classification.classification(actor, id, workingCopy)

    @PutMapping("/api/v1/objects/{id}/classification")
    fun classify(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: ClassifyPart,
    ) = classification.classify(actor, id, request.classId, request.values)
}

data class SearchClass(
    val filters: List<com.plm.classification.api.ClassFilter> = emptyList(),
    val limit: Int = 200,
)

/** Classification search and find similar (roadmap 7.12). */
@RestController
internal class ClassSearchController(
    private val search: com.plm.classification.api.ClassSearchService,
) {
    @PostMapping("/api/v1/classes/{id}/search")
    fun search(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SearchClass,
    ) = search.search(actor, id, request.filters, request.limit)

    @GetMapping("/api/v1/objects/{id}/similar")
    fun similar(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "10") limit: Int,
    ) = search.similar(actor, id, limit)
}
