package com.plm.standards.domain

/** The namespaces OSLC resources use (OSLC Core 3.0, Architecture and Change Management). */
internal val NAMESPACES =
    linkedMapOf(
        "rdf" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "rdfs" to "http://www.w3.org/2000/01/rdf-schema#",
        "dcterms" to "http://purl.org/dc/terms/",
        "oslc" to "http://open-services.net/ns/core#",
        "oslc_am" to "http://open-services.net/ns/am#",
        "oslc_cm" to "http://open-services.net/ns/cm#",
        "oslc_rm" to "http://open-services.net/ns/rm#",
        "oslc_qm" to "http://open-services.net/ns/qm#",
        "foaf" to "http://xmlns.com/foaf/0.1/",
        "xsd" to "http://www.w3.org/2001/XMLSchema#",
        "plm" to "urn:plm:ns#",
    )

/** An RDF object: a link, a typed literal, or a nested (blank-node) resource. */
internal sealed interface RdfValue {
    data class Iri(
        val iri: String,
    ) : RdfValue

    data class Literal(
        val value: String,
        /** e.g. `xsd:dateTime`; null for a plain string. */
        val datatype: String? = null,
    ) : RdfValue

    data class Node(
        val resource: RdfResource,
    ) : RdfValue
}

/** A resource as a tree: what OSLC documents are (a subject, its properties, nested blank nodes). */
internal class RdfResource(
    /** Null: a blank node. */
    val about: String?,
    val types: List<String>,
) {
    val properties = mutableListOf<Pair<String, RdfValue>>()

    fun text(
        p: String,
        v: String?,
    ) = apply { if (v != null) properties += p to RdfValue.Literal(v) }

    fun typed(
        p: String,
        v: String,
        type: String,
    ) = apply { properties += p to RdfValue.Literal(v, type) }

    fun link(
        p: String,
        iri: String,
    ) = apply { properties += p to RdfValue.Iri(iri) }

    fun node(
        p: String,
        r: RdfResource,
    ) = apply { properties += p to RdfValue.Node(r) }
}

/** Expands `oslc:Resource` to its IRI; an IRI already absolute stays as it is. */
internal fun expand(curie: String): String {
    val prefix = curie.substringBefore(':', "")
    return NAMESPACES[prefix]?.let { it + curie.substringAfter(':') } ?: curie
}

/** Writes resources as RDF/XML (OSLC 2.0's required format), Turtle or JSON-LD. */
internal object RdfWriter {
    fun write(
        r: RdfResource,
        mediaType: String,
    ): String =
        when {
            mediaType.contains("turtle") -> turtle(r)
            mediaType.contains("json") -> jsonLd(r)
            else -> rdfXml(r)
        }

    private fun xml(s: String) =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    fun rdfXml(r: RdfResource): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rdf:RDF")
        NAMESPACES.forEach { (p, ns) ->
            sb
                .append("\n    xmlns:")
                .append(p)
                .append("=\"")
                .append(ns)
                .append('"')
        }
        sb.append(">\n")
        description(sb, r, 1)
        return sb.append("</rdf:RDF>\n").toString()
    }

    private fun description(
        sb: StringBuilder,
        r: RdfResource,
        depth: Int,
    ) {
        val pad = "  ".repeat(depth)
        sb.append(pad).append("<rdf:Description")
        if (r.about != null) sb.append(" rdf:about=\"").append(xml(r.about)).append('"')
        sb.append(">\n")
        r.types.forEach {
            sb
                .append(
                    pad,
                ).append("  <rdf:type rdf:resource=\"")
                .append(xml(expand(it)))
                .append("\"/>\n")
        }
        for ((p, v) in r.properties) {
            when (v) {
                is RdfValue.Iri ->
                    sb
                        .append(
                            pad,
                        ).append("  <$p rdf:resource=\"")
                        .append(xml(expand(v.iri)))
                        .append("\"/>\n")
                is RdfValue.Literal -> {
                    sb.append(pad).append("  <$p")
                    if (v.datatype != null) sb.append(" rdf:datatype=\"").append(xml(expand(v.datatype))).append('"')
                    sb.append('>').append(xml(v.value)).append("</$p>\n")
                }
                is RdfValue.Node -> {
                    sb.append(pad).append("  <$p>\n")
                    description(sb, v.resource, depth + 2)
                    sb.append(pad).append("  </$p>\n")
                }
            }
        }
        sb.append(pad).append("</rdf:Description>\n")
    }

    private fun ttl(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    fun turtle(r: RdfResource): String {
        val sb = StringBuilder()
        NAMESPACES.forEach { (p, ns) -> sb.append("@prefix $p: <$ns> .\n") }
        sb.append('\n')
        sb.append(if (r.about != null) "<${r.about}>" else "[]")
        body(sb, r, 1)
        return sb.append(" .\n").toString()
    }

    private fun body(
        sb: StringBuilder,
        r: RdfResource,
        depth: Int,
    ) {
        val pad = "    ".repeat(depth)
        val parts = mutableListOf<String>()
        if (r.types.isNotEmpty()) parts += "a " + r.types.joinToString(", ")
        for ((p, v) in r.properties) {
            parts +=
                when (v) {
                    is RdfValue.Iri ->
                        "$p " +
                            (if (v.iri.contains("://") || v.iri.startsWith("urn:")) "<${v.iri}>" else v.iri)
                    is RdfValue.Literal -> "$p " + ttl(v.value) + (v.datatype?.let { "^^$it" } ?: "")
                    is RdfValue.Node -> {
                        val inner = StringBuilder("[")
                        body(inner, v.resource, depth + 1)
                        inner.append("\n").append(pad).append("]")
                        "$p $inner"
                    }
                }
        }
        sb.append(parts.joinToString(" ;") { "\n$pad$it" })
    }

    fun jsonLd(r: RdfResource): String {
        val context = NAMESPACES.entries.joinToString(",\n    ") { (p, ns) -> "\"$p\": \"$ns\"" }
        return "{\n  \"@context\": {\n    $context\n  },\n" + jsonBody(r, 1) + "\n}\n"
    }

    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun jsonBody(
        r: RdfResource,
        depth: Int,
    ): String {
        val pad = "  ".repeat(depth)
        val fields = mutableListOf<String>()
        if (r.about != null) fields += "${js("@id")}: ${js(r.about)}"
        if (r.types.isNotEmpty()) fields += "${js("@type")}: [${r.types.joinToString(", ") { js(it) }}]"
        for ((p, values) in r.properties.groupBy({ it.first }, { it.second })) {
            val rendered =
                values.map { v ->
                    when (v) {
                        is RdfValue.Iri -> "{ \"@id\": ${js(v.iri)} }"
                        is RdfValue.Literal ->
                            if (v.datatype ==
                                null
                            ) {
                                js(v.value)
                            } else {
                                "{ \"@value\": ${js(v.value)}, \"@type\": ${js(v.datatype)} }"
                            }
                        is RdfValue.Node -> "{\n" + jsonBody(v.resource, depth + 1) + "\n$pad}"
                    }
                }
            fields += "${js(p)}: " + if (rendered.size == 1) rendered.first() else rendered.joinToString(", ", "[", "]")
        }
        return fields.joinToString(",\n") { "$pad$it" }
    }
}
