package com.plm.query.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/*
 * The report builder's query engine: a query over versioned objects and the links between them,
 * kept as data (never SQL from a user), compiled to one SQL statement with every object's access
 * check inside it.
 */

/** What a field holds: it decides the operators, aggregates and how values are shown. */
enum class FieldType {
    TEXT,
    NUMBER,
    DATE,
    BOOLEAN,

    /** One of [FieldSpec.values]. */
    ENUM,

    /** A type id, shown by name; "equals" includes subtypes. */
    TYPE,

    /** A context id, shown by name. */
    CONTEXT,

    /** A principal id, shown by name. */
    PRINCIPAL,

    /** A lifecycle state key. */
    STATE,
}

data class FieldValue(
    val key: String,
    val label: String,
)

/**
 * A field of an object or a link. [sql] is a SQL expression template (trusted code, never user
 * input): `{m}` is the object's master row, `{i}` the version picked, `{l}` the link row.
 */
data class FieldSpec(
    val key: String,
    val label: String,
    val type: FieldType,
    val sql: String,
    val values: List<FieldValue> = emptyList(),
    val unit: String? = null,
)

/**
 * A link from one object to another, stored by the contributing module. [table] is joined as `{l}`
 * where [on] holds; `{fm}` and `{fi}` are the source object's master row and version. The target
 * is [toMaster] (a master id over `{l}`: its version is picked by the query's version rule) or
 * [toIteration] (the version the link names, e.g. the parent version that holds a structure line).
 */
data class RelationSpec(
    val key: String,
    val label: String,
    val from: Set<String> = emptySet(),
    val to: Set<String> = emptySet(),
    val table: String,
    val on: String,
    val toMaster: String? = null,
    val toIteration: String? = null,
    val fields: List<FieldSpec> = emptyList(),
)

/**
 * How versioned objects are stored and their versions picked: contributed by the versioning module.
 * Templates use `{m}` and `{i}`; [picks] are conditions on `{i}` given `{m}`, by [VersionMode],
 * with the parameters `:q_state` and `:q_as_of`.
 */
data class ObjectSource(
    val masterTable: String,
    val iterationTable: String,
    val masterOfIteration: String,
    val picks: Map<VersionMode, String>,
    val tenant: String,
    val typeId: String,
    val contextId: String,
    val state: String,
    val id: String,
    val attributes: String,
    val fields: List<FieldSpec>,
)

/** What a module adds to the query engine. */
interface QueryContributor {
    fun objectSource(): ObjectSource? = null

    fun relations(): List<RelationSpec> = emptyList()

    /** Fields every object has besides its type's attributes (classification, …). */
    fun fields(actor: Actor): List<FieldSpec> = emptyList()
}

// ---- the query, as saved in a report ----

/** Which version of each object a query looks at. */
enum class VersionMode {
    /** The latest version (a working copy only for whoever has it checked out). */
    LATEST,

    /** The latest version in [VersionRule.state] (e.g. the latest released). */
    STATE,

    /** The latest version checked in by [VersionRule.asOf]. */
    AS_OF,
}

data class VersionRule(
    val mode: VersionMode = VersionMode.LATEST,
    val state: String? = null,
    val asOf: Instant? = null,
)

/** The objects a query starts from: [alias] (e.g. `part`), of [typeId] or a subtype (null: any). */
data class QuerySource(
    val alias: String = "o",
    val typeId: UUID? = null,
)

/** Follows [relation] from the object [from] (an alias) to objects named [alias]. */
data class QueryLink(
    val alias: String,
    val from: String,
    val relation: String,
    val typeId: UUID? = null,
    /** Keep the source objects that have no such link (their columns here are empty). */
    val optional: Boolean = false,
)

/** A field of the object (or link) named [alias]: `number`, `attr:mass`, `class:capacity`, `link:quantity`. */
data class FieldRef(
    val alias: String,
    val field: String,
)

enum class Operator {
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    BETWEEN,
    CONTAINS,
    STARTS_WITH,
    IN,
    EMPTY,
    NOT_EMPTY,

    /** A date in the last [value] days. */
    LAST_DAYS,
    IS_TRUE,
    IS_FALSE,
}

enum class GroupOp { AND, OR, NOT }

/**
 * Either a group ([group] with [conditions]: all, any, or none of them) or a comparison of [field]
 * with [value] (a list for IN and BETWEEN) or with the run's [parameter].
 */
data class Condition(
    val group: GroupOp? = null,
    val conditions: List<Condition> = emptyList(),
    val field: FieldRef? = null,
    val operator: Operator? = null,
    val value: Any? = null,
    val parameter: String? = null,
)

enum class Aggregate { COUNT, COUNT_DISTINCT, SUM, AVG, MIN, MAX }

data class QueryColumn(
    val field: FieldRef,
    val label: String? = null,
    val aggregate: Aggregate? = null,
)

data class QuerySort(
    /** Index into [QueryDefinition.columns]. */
    val column: Int,
    val descending: Boolean = false,
)

/** A question the report asks when it runs, used by conditions as [Condition.parameter]. */
data class QueryParameter(
    val name: String,
    val label: String,
    val type: FieldType = FieldType.TEXT,
    /** Unanswered, its conditions are left out. */
    val required: Boolean = false,
    val defaultValue: Any? = null,
)

data class QueryDefinition(
    val from: QuerySource = QuerySource(),
    val links: List<QueryLink> = emptyList(),
    val where: Condition? = null,
    val columns: List<QueryColumn> = emptyList(),
    val sort: List<QuerySort> = emptyList(),
    val versions: VersionRule = VersionRule(),
    val parameters: List<QueryParameter> = emptyList(),
)

// ---- what it finds ----

data class ResultColumn(
    val label: String,
    val type: FieldType,
    val alias: String,
    val field: String,
    val aggregate: Aggregate?,
    val unit: String?,
)

data class ResultRow(
    /** One value per column: types, contexts and people by name; numbers as numbers. */
    val values: List<Any?>,
    /** The objects behind the row (alias → master id), to open them: empty when totalled. */
    val objects: Map<String, UUID>,
)

data class QueryResult(
    val columns: List<ResultColumn>,
    val rows: List<ResultRow>,
    /** More rows matched than were read. */
    val capped: Boolean,
)

/** A field as the builder offers it. */
data class FieldInfo(
    val key: String,
    val label: String,
    val type: FieldType,
    val values: List<FieldValue>,
    val unit: String?,
)

data class RelationInfo(
    val key: String,
    val label: String,
    val from: Set<String>,
    val to: Set<String>,
    val fields: List<FieldInfo>,
)

/**
 * Runs report builder queries (error codes: `INVALID_QUERY`, `QUERY_PARAMETER_MISSING`,
 * `QUERY_TOO_SLOW`). Every object is filtered by the actor's read access in the SQL itself.
 */
interface QueryService {
    /** The links the builder can follow. */
    fun relations(actor: Actor): List<RelationInfo>

    /** The fields of objects of [typeId] (null: any type): built-in, attributes, contributed. */
    fun fields(
        actor: Actor,
        typeId: UUID?,
    ): List<FieldInfo>

    /** @throws com.plm.platform.error.ValidationException `INVALID_QUERY` */
    fun validate(
        actor: Actor,
        definition: QueryDefinition,
    )

    fun run(
        actor: Actor,
        definition: QueryDefinition,
        parameters: Map<String, Any?> = emptyMap(),
        limit: Int = 1000,
    ): QueryResult
}
