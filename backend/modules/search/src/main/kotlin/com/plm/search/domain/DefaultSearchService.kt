package com.plm.search.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.search.api.FacetValue
import com.plm.search.api.SaveSearch
import com.plm.search.api.SavedSearch
import com.plm.search.api.SavedSearchService
import com.plm.search.api.SearchHit
import com.plm.search.api.SearchMatches
import com.plm.search.api.SearchQuery
import com.plm.search.api.SearchResults
import com.plm.search.api.SearchService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private const val CANDIDATES = 1000

@Service
internal class DefaultSearchService(
    private val index: SearchIndex,
    private val versioning: VersioningService,
    private val indexer: Indexer,
) : SearchService {
    override fun search(
        actor: Actor,
        query: SearchQuery,
    ): SearchResults {
        if ((query.text?.length ?: 0) >
            500
        ) {
            throw ValidationException("INVALID_QUERY", "Search text must be at most 500 characters")
        }
        val candidates = index.candidates(actor.tenant, query, CANDIDATES)
        // Only what the actor may read: checked in SQL with their access rules, 500 at a time.
        val readable =
            candidates
                .map { it.id }
                .chunked(500)
                .flatMap { ids -> versioning.list(actor, ObjectQuery(ids = ids.map(::MasterId), limit = 500)) }
                .associateBy { it.id.value }
        val found = candidates.mapNotNull { c -> readable[c.id]?.let { c to it } }
        val page = found.drop(query.offset.coerceAtLeast(0)).take(query.limit.coerceIn(1, 200))
        return SearchResults(
            total = found.size,
            capped = candidates.size >= CANDIDATES,
            hits = page.map { (c, o) -> hit(c, o) },
            facets =
                mapOf(
                    "type" to
                        facet(
                            found.map {
                                it.second.typeId.value
                                    .toString()
                            },
                        ),
                    "state" to facet(found.map { it.second.latest.state }),
                    "context" to
                        facet(
                            found.map {
                                it.second.contextId.value
                                    .toString()
                            },
                        ),
                ),
            engine = index.name,
        )
    }

    override fun matching(
        actor: Actor,
        query: SearchQuery,
    ): SearchMatches {
        val candidates = index.candidates(actor.tenant, query, CANDIDATES)
        val readable =
            candidates
                .map { it.id }
                .chunked(500)
                .flatMap { ids -> versioning.list(actor, ObjectQuery(ids = ids.map(::MasterId), limit = 500)) }
                .map { it.id.value }
                .toSet()
        return SearchMatches(candidates.map { it.id }.filter { it in readable }, candidates.size >= CANDIDATES)
    }

    private fun facet(keys: List<String>) =
        keys
            .groupingBy {
                it
            }.eachCount()
            .map { FacetValue(it.key, it.value) }
            .sortedByDescending { it.count }

    private fun hit(
        c: Candidate,
        o: VersionedObject,
    ) = SearchHit(
        id = o.id.value,
        number = o.number,
        name = o.name,
        typeId = o.typeId.value,
        contextId = o.contextId.value,
        state = o.latest.state,
        version = o.latest.version,
        modifiedAt = o.latest.checkedInAt ?: o.latest.createdAt,
        snippet = c.snippet?.takeIf { it.contains('«') },
        score = c.score,
    )

    override fun reindex(actor: Actor): Int {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators rebuild the search index",
            )
        }
        return indexer.queueAll(actor.tenant)
    }
}

@Service
@Transactional
internal class DefaultSavedSearchService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : SavedSearchService {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<SavedSearch> =
        jdbc
            .sql(
                """
                select * from saved_search where tenant_id = :tenant and (owner = :me or shared)
                order by owner = :me desc, lower(name)
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("me", actor.principal.value)
            .query { rs, _ ->
                SavedSearch(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    owner = rs.getString("owner"),
                    shared = rs.getBoolean("shared"),
                    query = json.readValue(rs.getString("query"), SearchQuery::class.java),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }.list()

    override fun save(
        actor: Actor,
        request: SaveSearch,
    ): SavedSearch = write(actor, null, request)

    override fun update(
        actor: Actor,
        id: UUID,
        request: SaveSearch,
    ): SavedSearch = write(actor, id, request)

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        mine(actor, id)
        jdbc.sql("delete from saved_search where id = :id").param("id", id).update()
    }

    private fun mine(
        actor: Actor,
        id: UUID,
    ): SavedSearch {
        val s =
            list(actor).firstOrNull { it.id == id }
                ?: throw NotFoundException("SAVED_SEARCH_NOT_FOUND", "There's no such saved search")
        if (s.owner != actor.principal.value) throw ForbiddenException("ACCESS_DENIED", "Only its owner can change it")
        return s
    }

    private fun write(
        actor: Actor,
        id: UUID?,
        request: SaveSearch,
    ): SavedSearch {
        val name = request.name.trim()
        if (name.isEmpty() ||
            name.length > 100
        ) {
            throw ValidationException("INVALID_SAVED_SEARCH", "Give the search a name")
        }
        if (id != null) mine(actor, id)
        val now = Timestamp.from(clock.instant())
        val newId = id ?: UuidV7.generate(clock)
        try {
            jdbc
                .sql(
                    """
                    insert into saved_search (id, tenant_id, owner, name, query, shared, created_at, updated_at)
                    values (:id, :tenant, :owner, :name, cast(:query as jsonb), :shared, :now, :now)
                    on conflict (id) do update set name = excluded.name, query = excluded.query, shared = excluded.shared,
                        updated_at = excluded.updated_at
                    """.trimIndent(),
                ).param("id", newId)
                .param("tenant", actor.tenant.value)
                .param("owner", actor.principal.value)
                .param("name", name)
                .param("query", json.writeValueAsString(request.query.copy(offset = 0)))
                .param("shared", request.shared)
                .param("now", now)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("DUPLICATE_SAVED_SEARCH", "You already have a search named $name")
        }
        return list(actor).first { it.id == newId }
    }
}
