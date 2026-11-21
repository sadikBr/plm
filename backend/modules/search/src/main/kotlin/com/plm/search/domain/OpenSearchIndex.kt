package com.plm.search.domain

import com.plm.platform.id.TenantId
import com.plm.search.api.SearchQuery
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

private val log = LoggerFactory.getLogger(OpenSearchIndex::class.java)

/**
 * OpenSearch (roadmap 3.6), over its REST API. One index for all tenants, filtered by tenant;
 * attribute values kept as `name=value` keywords for exact filtering.
 */
internal class OpenSearchIndex(
    private val baseUrl: String,
    private val index: String,
) : SearchIndex {
    override val name = "opensearch"
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    init {
        val exists = runCatching { call("HEAD", "/$index", null).statusCode() == 200 }.getOrElse { false }
        if (!exists) {
            val mapping =
                mapOf(
                    "settings" to mapOf("index" to mapOf("number_of_shards" to 1, "number_of_replicas" to 0)),
                    "mappings" to
                        mapOf(
                            "properties" to
                                mapOf(
                                    "tenant" to mapOf("type" to "keyword"),
                                    "context" to mapOf("type" to "keyword"),
                                    "type" to mapOf("type" to "keyword"),
                                    "state" to mapOf("type" to "keyword"),
                                    "number" to
                                        mapOf("type" to "text", "fields" to mapOf("raw" to mapOf("type" to "keyword"))),
                                    "name" to mapOf("type" to "text"),
                                    "body" to mapOf("type" to "text"),
                                    "attrs" to mapOf("type" to "keyword"),
                                    "modified" to mapOf("type" to "date"),
                                ),
                        ),
                )
            runCatching { call("PUT", "/$index", json.writeValueAsString(mapping)) }
                .onFailure { log.warn("Couldn't create search index {}: {}", index, it.toString()) }
        }
    }

    private fun call(
        method: String,
        path: String,
        body: String?,
        contentType: String = "application/json",
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("$baseUrl$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .method(
                    method,
                    if (body ==
                        null
                    ) {
                        HttpRequest.BodyPublishers.noBody()
                    } else {
                        HttpRequest.BodyPublishers.ofString(body)
                    },
                ).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    override fun upsert(docs: List<IndexDocument>) {
        if (docs.isEmpty()) return
        val bulk =
            buildString {
                for (d in docs) {
                    append(
                        json.writeValueAsString(
                            mapOf(
                                "index" to mapOf("_index" to index, "_id" to d.objectId.toString()),
                            ),
                        ),
                    ).append('\n')
                    append(
                        json.writeValueAsString(
                            mapOf(
                                "tenant" to d.tenant.value.toString(),
                                "context" to d.contextId.toString(),
                                "type" to d.typeId.toString(),
                                "state" to d.state,
                                "number" to d.number,
                                "name" to d.name,
                                "body" to d.body,
                                "attrs" to d.attributes.map { (k, v) -> "$k=${v.lowercase()}" },
                                "modified" to d.modifiedAt.toString(),
                            ),
                        ),
                    ).append('\n')
                }
            }
        val r = call("POST", "/_bulk?refresh=wait_for", bulk, "application/x-ndjson")
        if (r.statusCode() >= 300 ||
            r.body().contains("\"errors\":true")
        ) {
            error("OpenSearch refused the documents: ${r.body().take(300)}")
        }
    }

    override fun delete(
        tenant: TenantId,
        ids: List<UUID>,
    ) {
        if (ids.isEmpty()) return
        val bulk =
            ids.joinToString("") {
                json.writeValueAsString(
                    mapOf(
                        "delete" to mapOf("_index" to index, "_id" to it.toString()),
                    ),
                ) +
                    "\n"
            }
        call("POST", "/_bulk?refresh=wait_for", bulk, "application/x-ndjson")
    }

    @Suppress("UNCHECKED_CAST")
    override fun candidates(
        tenant: TenantId,
        query: SearchQuery,
        max: Int,
    ): List<Candidate> {
        val text = query.text?.trim()?.takeIf { it.isNotEmpty() }
        val filters = mutableListOf<Map<String, Any?>>(mapOf("term" to mapOf("tenant" to tenant.value.toString())))
        if (query.typeIds.isNotEmpty()) {
            filters +=
                mapOf("terms" to mapOf("type" to query.typeIds.map { it.toString() }))
        }
        if (query.states.isNotEmpty()) filters += mapOf("terms" to mapOf("state" to query.states))
        if (query.contextIds.isNotEmpty()) {
            filters +=
                mapOf("terms" to mapOf("context" to query.contextIds.map { it.toString() }))
        }
        query.attributes.forEach { (k, v) ->
            filters +=
                mapOf("wildcard" to mapOf("attrs" to mapOf("value" to "$k=*${v.lowercase()}*")))
        }
        val must =
            if (text != null) {
                listOf(
                    mapOf(
                        "bool" to
                            mapOf(
                                "should" to
                                    listOf(
                                        mapOf(
                                            "simple_query_string" to
                                                mapOf(
                                                    "query" to text,
                                                    "fields" to listOf("number^4", "name^3", "body"),
                                                    "default_operator" to "and",
                                                ),
                                        ),
                                        mapOf(
                                            "wildcard" to
                                                mapOf(
                                                    "number.raw" to
                                                        mapOf(
                                                            "value" to "*$text*",
                                                            "case_insensitive" to true,
                                                            "boost" to 2,
                                                        ),
                                                ),
                                        ),
                                    ),
                            ),
                    ),
                )
            } else {
                listOf(mapOf("match_all" to emptyMap<String, Any>()))
            }
        val sort =
            when (query.sort) {
                "MODIFIED" -> listOf(mapOf("modified" to "desc"))
                "NUMBER" -> listOf(mapOf("number.raw" to "asc"))
                else ->
                    if (text !=
                        null
                    ) {
                        listOf("_score", mapOf("modified" to "desc"))
                    } else {
                        listOf(mapOf("modified" to "desc"))
                    }
            }
        val body =
            mapOf(
                "size" to max,
                "_source" to false,
                "query" to mapOf("bool" to mapOf("must" to must, "filter" to filters)),
                "sort" to sort,
                "highlight" to
                    mapOf(
                        "pre_tags" to listOf("«"),
                        "post_tags" to listOf("»"),
                        "fields" to mapOf("body" to mapOf("fragment_size" to 120, "number_of_fragments" to 2)),
                    ),
            )
        val r = call("POST", "/$index/_search", json.writeValueAsString(body))
        if (r.statusCode() >= 300) error("OpenSearch search failed: ${r.body().take(300)}")
        val hits = (
            (
                json.readValue(
                    r.body(),
                    Map::class.java,
                )["hits"] as Map<String, Any?>
            )["hits"] as List<Map<String, Any?>>
        )
        return hits.map { h ->
            val highlight = (h["highlight"] as Map<String, List<String>>?)?.get("body")?.joinToString(" … ")
            Candidate(UUID.fromString(h["_id"] as String), (h["_score"] as Number?)?.toDouble() ?: 0.0, highlight)
        }
    }
}
