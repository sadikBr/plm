package com.plm.standards.infra.web

import com.plm.platform.actor.Actor
import com.plm.standards.api.AddExternalLink
import com.plm.standards.api.ExternalLinkTypes
import com.plm.standards.api.StandardsService
import com.plm.standards.domain.OslcResources
import com.plm.standards.domain.RdfResource
import com.plm.standards.domain.RdfWriter
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** STEP AP242 export and external links (roadmap 9.6). */
@RestController
internal class StandardsController(
    private val standards: StandardsService,
) {
    @GetMapping("/api/v1/objects/{id}/step-export", produces = ["application/zip"])
    fun step(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") released: Boolean,
        @RequestParam(defaultValue = "true") files: Boolean,
    ): ResponseEntity<ByteArray> {
        val export = standards.stepExport(actor, id, released, files)
        return ResponseEntity
            .ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(export.fileName)
                    .build()
                    .toString(),
            ).header("X-PLM-Products", export.products.toString())
            .header("X-PLM-Occurrences", export.occurrences.toString())
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(export.bytes)
    }

    @GetMapping("/api/v1/external-link-types")
    fun types() = ExternalLinkTypes.ALL

    @GetMapping("/api/v1/objects/{id}/external-links")
    fun links(
        actor: Actor,
        @PathVariable id: UUID,
    ) = standards.links(actor, id)

    @PostMapping("/api/v1/objects/{id}/external-links")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody command: AddExternalLink,
    ) = standards.addLink(actor, id, command)

    @DeleteMapping("/api/v1/objects/{id}/external-links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable linkId: UUID,
    ) = standards.removeLink(actor, id, linkId)
}

/**
 * OSLC Core (roadmap 9.6): RDF/XML by default, Turtle or JSON-LD when asked (Accept), the compact
 * form for `application/x-oslc-compact+xml`. Authenticated with a bearer token like the rest of the API.
 */
@RestController
internal class OslcController(
    private val oslc: OslcResources,
) {
    private fun respond(
        accept: String?,
        r: RdfResource,
    ): ResponseEntity<String> {
        val a = accept.orEmpty().lowercase()
        val type =
            when {
                a.contains("text/turtle") -> "text/turtle"
                a.contains(
                    "application/ld+json",
                ) ||
                    (a.contains("application/json") && !a.contains("rdf+xml")) -> "application/ld+json"
                else -> "application/rdf+xml"
            }
        return ResponseEntity
            .ok()
            .header("OSLC-Core-Version", "2.0")
            .contentType(MediaType.parseMediaType("$type;charset=UTF-8"))
            .body(RdfWriter.write(r, type))
    }

    /** Where resources live: the selection dialog answers with these addresses. */
    @GetMapping("/api/v1/oslc/config")
    fun config(actor: Actor) =
        mapOf(
            "resourceBase" to oslc.resourceUri(UUID(0, 0)).substringBeforeLast('/') + "/",
            "catalog" to oslc.catalogUri(),
        )

    @GetMapping("/api/v1/oslc/catalog")
    fun catalog(
        actor: Actor,
        @RequestHeader(HttpHeaders.ACCEPT, required = false) accept: String?,
    ) = respond(accept, oslc.catalog(actor))

    @GetMapping("/api/v1/oslc/contexts/{id}/provider")
    fun provider(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.ACCEPT, required = false) accept: String?,
    ) = respond(accept, oslc.provider(actor, id))

    @GetMapping("/api/v1/oslc/contexts/{id}/query")
    fun query(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "am") domain: String,
        @RequestParam(name = "oslc.where", required = false) where: String?,
        @RequestParam(name = "oslc.searchTerms", required = false) searchTerms: String?,
        @RequestHeader(HttpHeaders.ACCEPT, required = false) accept: String?,
    ) = respond(accept, oslc.query(actor, id, domain, where, searchTerms))

    @GetMapping("/api/v1/oslc/resources/{id}")
    fun resource(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.ACCEPT, required = false) accept: String?,
    ): ResponseEntity<String> =
        if (accept.orEmpty().contains("x-oslc-compact")) {
            ResponseEntity
                .ok()
                .header("OSLC-Core-Version", "2.0")
                .contentType(MediaType.parseMediaType("application/x-oslc-compact+xml;charset=UTF-8"))
                .body(RdfWriter.rdfXml(oslc.compact(actor, id)))
        } else {
            respond(accept, oslc.resource(actor, id))
        }
}
