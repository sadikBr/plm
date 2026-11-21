package com.plm.bom.infra.web

import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.DocumentLinkService
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class LinkDocument(
    val kind: DocumentLinkKind,
    val documentId: UUID,
)

data class LinkRuleRequest(
    val partTypeId: UUID,
    val documentTypeId: UUID,
    val kind: DocumentLinkKind,
)

@RestController
internal class DocumentLinkController(
    private val links: DocumentLinkService,
) {
    @GetMapping("/api/v1/objects/{id}/document-links")
    fun links(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(required = false) iterationId: UUID?,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = links.links(actor, MasterId(id), iterationId, workingCopy)

    /** For a document: the parts linking to it. */
    @GetMapping("/api/v1/objects/{id}/linking-parts")
    fun linkingParts(
        actor: Actor,
        @PathVariable id: UUID,
    ) = links.linkingParts(actor, MasterId(id))

    @PostMapping("/api/v1/objects/{id}/document-links")
    @ResponseStatus(HttpStatus.CREATED)
    fun link(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: LinkDocument,
    ) = links.link(actor, MasterId(id), request.kind, request.documentId)

    @PostMapping("/api/v1/objects/{id}/document-links/{linkId}/update-to-latest")
    fun updateToLatest(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable linkId: UUID,
    ) = links.updateToLatest(actor, MasterId(id), linkId)

    @DeleteMapping("/api/v1/objects/{id}/document-links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unlink(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable linkId: UUID,
    ) = links.unlink(actor, MasterId(id), linkId)

    @GetMapping("/api/v1/document-link-rules")
    fun rules(actor: Actor) = links.rules(actor)

    @PostMapping("/api/v1/document-link-rules")
    @ResponseStatus(HttpStatus.CREATED)
    fun addRule(
        actor: Actor,
        @RequestBody request: LinkRuleRequest,
    ) = links.addRule(actor, request.partTypeId, request.documentTypeId, request.kind)

    @DeleteMapping("/api/v1/document-link-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeRule(
        actor: Actor,
        @PathVariable ruleId: UUID,
    ) = links.removeRule(actor, ruleId)
}
