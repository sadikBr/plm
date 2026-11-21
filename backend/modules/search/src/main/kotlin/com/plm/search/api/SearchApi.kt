package com.plm.search.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

data class SearchQuery(
    /** Words to find anywhere: number, name, values, file names and file contents. Quotes, OR and - work. */
    val text: String? = null,
    val typeIds: List<UUID> = emptyList(),
    val states: List<String> = emptyList(),
    val contextIds: List<UUID> = emptyList(),
    /** Attribute name → value it must equal (as text, case-insensitive). */
    val attributes: Map<String, String> = emptyMap(),
    /** RELEVANCE (default with text), MODIFIED (newest first), NUMBER */
    val sort: String? = null,
    val offset: Int = 0,
    val limit: Int = 25,
)

data class SearchHit(
    val id: UUID,
    val number: String,
    val name: String,
    val typeId: UUID,
    val contextId: UUID,
    val state: String,
    val version: String,
    val modifiedAt: Instant,
    /** Where the words were found, with them between « and ». */
    val snippet: String?,
    val score: Double,
)

data class FacetValue(
    val key: String,
    val count: Int,
)

data class SearchResults(
    /** Matching objects the actor can read (a lower bound with [capped]). */
    val total: Int,
    /** More than the engine's candidate limit matched: refine the search. */
    val capped: Boolean,
    val hits: List<SearchHit>,
    /** By type, state and context: counted over what the actor can read. */
    val facets: Map<String, List<FacetValue>>,
    val engine: String,
)

/**
 * Search (roadmap 3.6): full-text and attribute search over objects, with facets. Only what the
 * actor may read is found or counted. Indexed in the background from object events.
 */
interface SearchService {
    fun search(
        actor: Actor,
        query: SearchQuery,
    ): SearchResults

    /** Every match the actor can read (at most 1000), in order; `capped` when there were more. */
    fun matching(
        actor: Actor,
        query: SearchQuery,
    ): SearchMatches

    /** Queues every object of the tenant for indexing again (site administrators). */
    fun reindex(actor: Actor): Int
}

data class SearchMatches(
    val ids: List<UUID>,
    val capped: Boolean,
)

data class SavedSearch(
    val id: UUID,
    val name: String,
    val owner: String,
    val shared: Boolean,
    val query: SearchQuery,
    val updatedAt: Instant,
)

data class SaveSearch(
    val name: String,
    val query: SearchQuery,
    val shared: Boolean = false,
)

/** Saved searches: your own, and those others shared. Error codes: `SAVED_SEARCH_NOT_FOUND`, `INVALID_SAVED_SEARCH`, `DUPLICATE_SAVED_SEARCH`. */
interface SavedSearchService {
    fun list(actor: Actor): List<SavedSearch>

    fun save(
        actor: Actor,
        request: SaveSearch,
    ): SavedSearch

    fun update(
        actor: Actor,
        id: UUID,
        request: SaveSearch,
    ): SavedSearch

    fun delete(
        actor: Actor,
        id: UUID,
    )
}
