package com.plm.search.domain

import com.plm.platform.id.TenantId
import com.plm.search.api.SearchQuery
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.util.UUID

/**
 * The built-in engine: Postgres full-text search (`simple` configuration, so part numbers and
 * mixed-language text are kept as they are). Number and name weigh more than the rest.
 */
internal class PostgresSearchIndex(
    private val jdbc: JdbcClient,
) : SearchIndex {
    override val name = "postgres"
    private val json = JsonMapper.builder().build()

    override fun upsert(docs: List<IndexDocument>) {
        for (d in docs) {
            jdbc
                .sql(
                    """
                    insert into search_document (object_id, tenant_id, context_id, type_id, state, number, name, attributes, body,
                                                 document, modified_at, indexed_at)
                    values (:id, :tenant, :context, :type, :state, :number, :name, cast(:attrs as jsonb), :body,
                            setweight(to_tsvector('simple', :number || ' ' || :name), 'A') || setweight(to_tsvector('simple', :body), 'B'),
                            :modified, now())
                    on conflict (object_id) do update set context_id = excluded.context_id, type_id = excluded.type_id,
                        state = excluded.state, number = excluded.number, name = excluded.name, attributes = excluded.attributes,
                        body = excluded.body, document = excluded.document, modified_at = excluded.modified_at, indexed_at = now()
                    """.trimIndent(),
                ).param("id", d.objectId)
                .param("tenant", d.tenant.value)
                .param("context", d.contextId)
                .param("type", d.typeId)
                .param("state", d.state)
                .param("number", d.number)
                .param("name", d.name)
                .param("attrs", json.writeValueAsString(d.attributes.mapValues { it.value.lowercase() }))
                .param("body", d.body)
                .param("modified", Timestamp.from(d.modifiedAt))
                .update()
        }
    }

    override fun delete(
        tenant: TenantId,
        ids: List<UUID>,
    ) {
        if (ids.isEmpty()) return
        jdbc
            .sql("delete from search_document where tenant_id = :tenant and object_id = any(cast(:ids as uuid[]))")
            .param("tenant", tenant.value)
            .param("ids", ids.toTypedArray())
            .update()
    }

    override fun candidates(
        tenant: TenantId,
        query: SearchQuery,
        max: Int,
    ): List<Candidate> {
        val text = query.text?.trim()?.takeIf { it.isNotEmpty() }
        val where = mutableListOf("tenant_id = :tenant")
        val params = mutableMapOf<String, Any?>("tenant" to tenant.value, "max" to max)
        if (text != null) {
            // Words anywhere, or the text inside a number or name (part numbers are typed in pieces).
            where += "(document @@ websearch_to_tsquery('simple', :text) or number ilike :like or name ilike :like)"
            params["text"] = text
            params["like"] = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        if (query.typeIds.isNotEmpty()) {
            where += "type_id = any(cast(:types as uuid[]))"
            params["types"] = query.typeIds.toTypedArray()
        }
        if (query.states.isNotEmpty()) {
            where += "state = any(cast(:states as text[]))"
            params["states"] = query.states.toTypedArray()
        }
        if (query.contextIds.isNotEmpty()) {
            where += "context_id = any(cast(:contexts as uuid[]))"
            params["contexts"] = query.contextIds.toTypedArray()
        }
        query.attributes.entries.forEachIndexed { i, (k, v) ->
            where += "attributes ->> :ak$i like :av$i"
            params["ak$i"] = k
            params["av$i"] = "%" + v.lowercase().replace("%", "\\%").replace("_", "\\_") + "%"
        }
        val rank =
            if (text !=
                null
            ) {
                "ts_rank(document, websearch_to_tsquery('simple', :text)) + case when number ilike :like then 1 else 0 end"
            } else {
                "0"
            }
        val order =
            when (query.sort) {
                "MODIFIED" -> "modified_at desc"
                "NUMBER" -> "number"
                else -> if (text != null) "score desc, modified_at desc" else "modified_at desc"
            }
        val snippet =
            if (text != null) {
                "ts_headline('simple', body, websearch_to_tsquery('simple', :text), " +
                    "'StartSel=«, StopSel=», MaxFragments=2, MaxWords=18, MinWords=6, FragmentDelimiter= … ')"
            } else {
                "null"
            }
        return jdbc
            .sql(
                """
                select object_id, score, case when :hasText then $snippet end as snippet from (
                    select *, $rank as score from search_document where ${where.joinToString(" and ")}
                    order by $order limit :max
                ) found order by $order
                """.trimIndent(),
            ).params(params)
            .param("hasText", text != null)
            .query {
                rs,
                _,
                ->
                Candidate(rs.getObject("object_id", UUID::class.java), rs.getDouble("score"), rs.getString("snippet"))
            }.list()
    }
}
