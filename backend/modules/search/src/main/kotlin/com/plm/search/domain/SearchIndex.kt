package com.plm.search.domain

import com.plm.platform.id.TenantId
import com.plm.search.api.SearchQuery
import java.time.Instant
import java.util.UUID

/** What's indexed of an object: its latest version. */
internal data class IndexDocument(
    val objectId: UUID,
    val tenant: TenantId,
    val contextId: UUID,
    val typeId: UUID,
    val state: String,
    val number: String,
    val name: String,
    /** Values as text, by attribute name. */
    val attributes: Map<String, String>,
    /** Everything else searchable: values, file names, file contents. */
    val body: String,
    val modifiedAt: Instant,
)

internal data class Candidate(
    val id: UUID,
    val score: Double,
    val snippet: String?,
)

/** A search engine: keeps documents and finds candidates (access is checked afterwards). */
internal interface SearchIndex {
    val name: String

    fun upsert(docs: List<IndexDocument>)

    fun delete(
        tenant: TenantId,
        ids: List<UUID>,
    )

    /** Up to [max] matching objects, best first (or by [SearchQuery.sort]). */
    fun candidates(
        tenant: TenantId,
        query: SearchQuery,
        max: Int,
    ): List<Candidate>
}
