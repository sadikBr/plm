package com.plm.documents.infra.web

import com.plm.documents.api.CompareService
import com.plm.documents.api.CompareSide
import com.plm.documents.api.DocumentService
import com.plm.documents.api.NewFromTemplate
import com.plm.documents.api.SaveAs
import com.plm.documents.api.TemplateSettings
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersionedObject
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

@RestController
internal class DocumentController(
    private val documents: DocumentService,
    private val compare: CompareService,
) {
    /** Templates usable in `contextId` (and of `typeId`); all of them for administrators without a context. */
    @GetMapping("/api/v1/document-templates")
    fun templates(
        actor: Actor,
        @RequestParam(required = false) contextId: UUID?,
        @RequestParam(required = false) typeId: UUID?,
    ) = documents.templates(actor, contextId, typeId)

    @PostMapping("/api/v1/document-templates")
    @ResponseStatus(HttpStatus.CREATED)
    fun createTemplate(
        actor: Actor,
        @RequestBody settings: TemplateSettings,
    ) = documents.createTemplate(actor, settings)

    @PutMapping("/api/v1/document-templates/{id}")
    fun updateTemplate(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody settings: TemplateSettings,
    ) = documents.updateTemplate(actor, id, settings)

    @DeleteMapping("/api/v1/document-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTemplate(
        actor: Actor,
        @PathVariable id: UUID,
    ) = documents.deleteTemplate(actor, id)

    /** A new object from a template: its type, values and files. */
    @PostMapping("/api/v1/objects/from-template")
    @ResponseStatus(HttpStatus.CREATED)
    fun fromTemplate(
        actor: Actor,
        @RequestBody request: NewFromTemplate,
    ) = CreatedObject(documents.newFromTemplate(actor, request))

    @PostMapping("/api/v1/objects/{id}/save-as")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveAs(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveAs,
    ) = CreatedObject(documents.saveAs(actor, MasterId(id), request))

    /** Two versions side by side: latest of each object unless an iteration is given. */
    @GetMapping("/api/v1/compare")
    fun compare(
        actor: Actor,
        @RequestParam left: UUID,
        @RequestParam right: UUID,
        @RequestParam(required = false) leftIteration: UUID?,
        @RequestParam(required = false) rightIteration: UUID?,
    ) = compare.compare(actor, CompareSide(left, leftIteration), CompareSide(right, rightIteration))
}

/** The object just made: open it with its id. */
internal data class CreatedObject(
    val id: UUID,
    val number: String,
    val name: String,
    val version: String,
) {
    constructor(o: VersionedObject) : this(o.id.value, o.number, o.name, o.latest.version)
}
