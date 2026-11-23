package com.plm.standards.domain

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.identity.api.PrincipalDirectory
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** `dcterms:identifier="P-100"` and `dcterms:title="…"` terms joined by `and` (OSLC query syntax, the part tools use). */
private val WHERE = Regex("""^\s*(dcterms:identifier|dcterms:title)\s*=\s*"((?:[^"\\]|\\.)*)"\s*$""")

/**
 * OSLC Core services (roadmap 9.6), so requirements, test and change tools can find and link to
 * parts, documents and changes: a service provider catalog, one service provider per product or
 * library (Architecture Management and Change Management domains, each with a query capability
 * and a delegated selection dialog), resources and their compact (preview) form.
 */
@Component
@Transactional(readOnly = true)
internal class OslcResources(
    private val contexts: ContextService,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val principals: PrincipalDirectory,
    private val links: ExternalLinkRepository,
    @param:Value("\${plm.public-url:http://localhost:8080}") private val publicUrl: String,
    @param:Value("\${plm.ui-url:http://localhost:5173}") private val uiUrl: String,
) {
    private val api get() = publicUrl.trimEnd('/') + "/api/v1/oslc"
    private val ui get() = uiUrl.trimEnd('/')

    fun resourceUri(id: UUID) = "$api/resources/$id"

    fun catalogUri() = "$api/catalog"

    private fun providerUri(id: UUID) = "$api/contexts/$id/provider"

    private fun holders(actor: Actor) =
        listOf(
            ContextKind.PRODUCT,
            ContextKind.LIBRARY,
            ContextKind.PROJECT,
        ).flatMap { contexts.search(actor, "", 500, it) }

    fun catalog(actor: Actor): RdfResource {
        val catalog =
            RdfResource("$api/catalog", listOf("oslc:ServiceProviderCatalog"))
                .text("dcterms:title", "PLM service providers")
                .text("dcterms:description", "One service provider per product, library and project you can see")
        for (c in holders(actor)) {
            catalog.node(
                "oslc:serviceProvider",
                RdfResource(providerUri(c.id.value), listOf("oslc:ServiceProvider")).text("dcterms:title", c.name),
            )
        }
        return catalog.link("oslc:domain", "oslc_am:").link("oslc:domain", "oslc_cm:")
    }

    fun provider(
        actor: Actor,
        contextId: UUID,
    ): RdfResource {
        val c = contexts.get(actor, ContextId(contextId))
        val provider =
            RdfResource(providerUri(c.id.value), listOf("oslc:ServiceProvider"))
                .text("dcterms:title", c.name)
                .link("oslc:details", "$ui/contexts/${c.id.value}")
        for ((domain, label, resourceType, kind) in listOf(
            Quad("oslc_am:", "Parts and documents", "oslc_am:Resource", "am"),
            Quad("oslc_cm:", "Changes", "oslc_cm:ChangeRequest", "cm"),
        )) {
            val service =
                RdfResource(null, listOf("oslc:Service"))
                    .link("oslc:domain", domain)
                    .node(
                        "oslc:queryCapability",
                        RdfResource(null, listOf("oslc:QueryCapability"))
                            .text("dcterms:title", "$label in ${c.name}")
                            .link("oslc:queryBase", "$api/contexts/${c.id.value}/query?domain=$kind")
                            .link("oslc:resourceType", resourceType),
                    ).node(
                        "oslc:selectionDialog",
                        RdfResource(null, listOf("oslc:Dialog"))
                            .text("dcterms:title", "Choose ${label.lowercase()} in ${c.name}")
                            .text("oslc:label", label)
                            .link("oslc:dialog", "$ui/oslc/select?contextId=${c.id.value}&domain=$kind")
                            .text("oslc:hintWidth", "640px")
                            .text("oslc:hintHeight", "480px")
                            .link("oslc:resourceType", resourceType),
                    )
            provider.node("oslc:service", service)
        }
        return provider
    }

    private data class Quad(
        val domain: String,
        val label: String,
        val type: String,
        val kind: String,
    )

    fun query(
        actor: Actor,
        contextId: UUID,
        domain: String,
        where: String?,
        searchTerms: String?,
    ): RdfResource {
        val c = contexts.get(actor, ContextId(contextId))
        val terms =
            where?.takeIf { it.isNotBlank() }?.split(Regex("\\s+and\\s+"))?.map {
                val m =
                    WHERE.matchEntire(it)
                        ?: throw ValidationException(
                            "INVALID_OSLC_QUERY",
                            "Only dcterms:identifier and dcterms:title = \"…\" are supported",
                        )
                m.groupValues[1] to m.groupValues[2].replace("\\\"", "\"")
            } ?: emptyList()
        val text = terms.firstOrNull()?.second ?: searchTerms?.trim()?.ifEmpty { null }
        val wantChanges = domain == "cm"
        val found =
            versioning
                .list(actor, ObjectQuery(contextId = c.id, text = text, limit = 100))
                .filter { o -> (types.get(actor, o.typeId).baseClass == BaseClass.CHANGE) == wantChanges }
                .filter { o ->
                    terms.all { (p, v) ->
                        if (p ==
                            "dcterms:identifier"
                        ) {
                            o.number.equals(v, true)
                        } else {
                            o.name.equals(v, true)
                        }
                    }
                }
        val result =
            RdfResource(
                "$api/contexts/${c.id.value}/query?domain=$domain",
                listOf("oslc:ResponseInfo"),
            ).text("oslc:totalCount", found.size.toString())
        found.forEach { result.node("rdfs:member", brief(actor, it)) }
        return result
    }

    private fun brief(
        actor: Actor,
        o: VersionedObject,
    ) = RdfResource(resourceUri(o.id.value), listOf(typeOf(actor, o)))
        .text("dcterms:identifier", o.number)
        .text("dcterms:title", o.name)
        .text("oslc:shortTitle", "${o.number} ${o.latest.version}")

    private fun typeOf(
        actor: Actor,
        o: VersionedObject,
    ) = if (types.get(actor, o.typeId).baseClass == BaseClass.CHANGE) "oslc_cm:ChangeRequest" else "oslc_am:Resource"

    fun resource(
        actor: Actor,
        id: UUID,
    ): RdfResource {
        val o = versioning.get(actor, MasterId(id))
        val type = types.get(actor, o.typeId)
        val r =
            RdfResource(resourceUri(id), listOf(typeOf(actor, o)))
                .text("dcterms:identifier", o.number)
                .text("dcterms:title", o.name)
                .text("dcterms:description", o.latest.attributes["description"]?.toString())
                .text("oslc:shortTitle", "${o.number} ${o.latest.version}")
                .typed("dcterms:created", o.createdAt.toString(), "xsd:dateTime")
                .typed("dcterms:modified", (o.latest.checkedInAt ?: o.latest.createdAt).toString(), "xsd:dateTime")
                .node(
                    "dcterms:creator",
                    RdfResource(
                        null,
                        listOf("foaf:Person"),
                    ).text("foaf:name", principals.displayName(actor.tenant, PrincipalId(o.createdBy))),
                ).text("dcterms:type", type.displayName)
                .text("plm:version", o.latest.version)
                .text("plm:state", o.latest.state)
                .link("oslc:serviceProvider", providerUri(o.contextId.value))
                .link("plm:page", "$ui/objects/$id")
        for (l in links.links(id)) r.link(l.linkType, l.url)
        return r
    }

    /** The compact form tools show on hover (OSLC Resource Preview). */
    fun compact(
        actor: Actor,
        id: UUID,
    ): RdfResource {
        val o = versioning.get(actor, MasterId(id))
        return RdfResource(resourceUri(id), listOf("oslc:Compact"))
            .text("dcterms:title", "${o.number} ${o.name}")
            .text("oslc:shortTitle", "${o.number} ${o.latest.version}")
            .link("oslc:icon", "$ui/favicon.svg")
            .node(
                "oslc:smallPreview",
                RdfResource(null, listOf("oslc:Preview"))
                    .link("oslc:document", "$ui/oslc/preview/$id")
                    .text("oslc:hintWidth", "420px")
                    .text("oslc:hintHeight", "220px"),
            )
    }
}
