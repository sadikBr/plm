package com.plm.query.infra.web

import com.plm.platform.actor.Actor
import com.plm.query.api.FieldInfo
import com.plm.query.api.QueryDefinition
import com.plm.query.api.QueryResult
import com.plm.query.api.QueryService
import com.plm.query.api.RelationInfo
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class RunQuery(
    val definition: QueryDefinition,
    val parameters: Map<String, Any?> = emptyMap(),
    val limit: Int = 200,
)

/** The report builder's vocabulary, and its live preview. */
@RestController
internal class QueryController(
    private val queries: QueryService,
) {
    /** The links a query can follow. */
    @GetMapping("/api/v1/query/relations")
    fun relations(actor: Actor): List<RelationInfo> = queries.relations(actor)

    /** The fields of objects of a type (or of any type). */
    @GetMapping("/api/v1/query/fields")
    fun fields(
        actor: Actor,
        @RequestParam(required = false) typeId: UUID?,
    ): List<FieldInfo> = queries.fields(actor, typeId)

    /** Runs a query without saving it (the builder's preview). */
    @PostMapping("/api/v1/query/run")
    fun run(
        actor: Actor,
        @RequestBody body: RunQuery,
    ): QueryResult = queries.run(actor, body.definition, body.parameters, body.limit.coerceIn(1, 1000))
}
