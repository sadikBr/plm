package com.plm.search.infra.web

import com.plm.platform.actor.Actor
import com.plm.search.api.SaveSearch
import com.plm.search.api.SavedSearchService
import com.plm.search.api.SearchQuery
import com.plm.search.api.SearchService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class SearchController(
    private val search: SearchService,
    private val saved: SavedSearchService,
) {
    /** Full-text and attribute search, with facets; only what you can read. */
    @PostMapping("/api/v1/search")
    fun search(
        actor: Actor,
        @RequestBody query: SearchQuery,
    ) = search.search(actor, query)

    @PostMapping("/api/v1/search/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun reindex(actor: Actor) = mapOf("queued" to search.reindex(actor))

    @GetMapping("/api/v1/saved-searches")
    fun list(actor: Actor) = saved.list(actor)

    @PostMapping("/api/v1/saved-searches")
    @ResponseStatus(HttpStatus.CREATED)
    fun save(
        actor: Actor,
        @RequestBody request: SaveSearch,
    ) = saved.save(actor, request)

    @PutMapping("/api/v1/saved-searches/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveSearch,
    ) = saved.update(actor, id, request)

    @DeleteMapping("/api/v1/saved-searches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = saved.delete(actor, id)
}
