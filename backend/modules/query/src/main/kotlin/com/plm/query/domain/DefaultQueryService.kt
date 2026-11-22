package com.plm.query.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.Permission
import com.plm.identity.api.PrincipalDirectory
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.query.api.Aggregate
import com.plm.query.api.Condition
import com.plm.query.api.FieldInfo
import com.plm.query.api.FieldSpec
import com.plm.query.api.FieldType
import com.plm.query.api.FieldValue
import com.plm.query.api.GroupOp
import com.plm.query.api.ObjectSource
import com.plm.query.api.Operator
import com.plm.query.api.QueryContributor
import com.plm.query.api.QueryDefinition
import com.plm.query.api.QueryResult
import com.plm.query.api.QueryService
import com.plm.query.api.RelationInfo
import com.plm.query.api.RelationSpec
import com.plm.query.api.ResultColumn
import com.plm.query.api.ResultRow
import com.plm.query.api.VersionMode
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeService
import com.plm.types.api.DataType
import com.plm.types.api.EnumerationService
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private val ALIAS = Regex("^[a-z][a-z0-9_]{0,30}$")
private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,62}$")
private const val MAX_LINKS = 8
private const val MAX_COLUMNS = 60
private const val MAX_CONDITIONS = 100

internal fun invalid(message: String): Nothing = throw ValidationException("INVALID_QUERY", message)

/** Which operators make sense for which kinds of field. */
internal val OPERATORS: Map<FieldType, Set<Operator>> =
    run {
        val empties = setOf(Operator.EMPTY, Operator.NOT_EMPTY)
        val ordered =
            setOf(Operator.EQ, Operator.NE, Operator.LT, Operator.LE, Operator.GT, Operator.GE, Operator.BETWEEN)
        val keys = setOf(Operator.EQ, Operator.NE, Operator.IN)
        mapOf(
            FieldType.TEXT to
                setOf(Operator.EQ, Operator.NE, Operator.CONTAINS, Operator.STARTS_WITH, Operator.IN) + empties,
            FieldType.NUMBER to ordered + empties,
            FieldType.DATE to ordered + empties + Operator.LAST_DAYS,
            FieldType.BOOLEAN to setOf(Operator.IS_TRUE, Operator.IS_FALSE) + empties,
            FieldType.ENUM to keys + empties,
            FieldType.TYPE to keys,
            FieldType.CONTEXT to keys,
            FieldType.PRINCIPAL to keys + empties,
            FieldType.STATE to keys,
        )
    }

@Service
@Transactional(readOnly = true)
internal class DefaultQueryService(
    private val jdbc: JdbcClient,
    private val contributors: ObjectProvider<QueryContributor>,
    private val access: AccessService,
    private val types: TypeService,
    private val attributes: AttributeService,
    private val enumerations: EnumerationService,
    private val contexts: ContextService,
    private val directory: PrincipalDirectory,
    private val clock: Clock,
) : QueryService {
    private fun source(): ObjectSource =
        contributors.orderedStream().toList().firstNotNullOfOrNull { it.objectSource() }
            ?: error("No module contributes how objects are stored")

    private fun relationSpecs(): Map<String, RelationSpec> =
        contributors
            .orderedStream()
            .toList()
            .flatMap { it.relations() }
            .associateBy { it.key }

    private fun info(f: FieldSpec) = FieldInfo(f.key, f.label, f.type, f.values, f.unit)

    override fun relations(actor: Actor): List<RelationInfo> =
        relationSpecs().values.map { r -> RelationInfo(r.key, r.label, r.from, r.to, r.fields.map(::info)) }

    override fun fields(
        actor: Actor,
        typeId: UUID?,
    ): List<FieldInfo> = objectFields(actor, typeId).map(::info)

    /** Built-in fields, the type's attributes, then what other modules add. */
    private fun objectFields(
        actor: Actor,
        typeId: UUID?,
    ): List<FieldSpec> {
        val src = source()
        val attrs =
            if (typeId == null) {
                emptyList()
            } else {
                attributes
                    .effective(actor, TypeId(typeId))
                    .filter { NAME.matches(it.name) }
                    .mapNotNull { attributeField(actor, src, it) }
            }
        return src.fields + attrs + contributors.orderedStream().toList().flatMap { it.fields(actor) }
    }

    private fun attributeField(
        actor: Actor,
        src: ObjectSource,
        a: AttributeDefinition,
    ): FieldSpec? {
        val json = "${src.attributes}->'${a.name}'"
        val text = "(${src.attributes}->>'${a.name}')"
        val label = a.labels["en"] ?: a.name
        val key = "attr:${a.name}"
        return when (a.dataType) {
            DataType.STRING, DataType.TEXT, DataType.URL, DataType.REFERENCE ->
                FieldSpec(
                    key,
                    label,
                    FieldType.TEXT,
                    text,
                )
            DataType.ENUMERATION -> {
                val values =
                    a.enumerationId
                        ?.let { id -> runCatching { enumerations.get(actor, id) }.getOrNull() }
                        ?.values
                        ?.map { FieldValue(it.key, it.labels["en"] ?: it.key) }
                        .orEmpty()
                FieldSpec(key, label, FieldType.ENUM, text, values)
            }
            DataType.INTEGER, DataType.REAL ->
                FieldSpec(
                    key,
                    label,
                    FieldType.NUMBER,
                    "(case when jsonb_typeof($json) = 'number' then $text::numeric end)",
                )
            DataType.QUANTITY ->
                FieldSpec(
                    key,
                    label,
                    FieldType.NUMBER,
                    "(case when jsonb_typeof($json->'value') = 'number' then ($json->>'value')::numeric end)",
                    unit = a.displayUnit,
                )
            DataType.BOOLEAN ->
                FieldSpec(
                    key,
                    label,
                    FieldType.BOOLEAN,
                    "(case when jsonb_typeof($json) = 'boolean' then $text::boolean end)",
                )
            DataType.DATE, DataType.TIMESTAMP ->
                FieldSpec(
                    key,
                    label,
                    FieldType.DATE,
                    "(case when $text ~ '^\\d{4}-\\d{2}-\\d{2}' then $text::timestamptz end)",
                )
        }
    }

    override fun validate(
        actor: Actor,
        definition: QueryDefinition,
    ) {
        Compilation(actor, definition, emptyMap(), checkOnly = true).compile()
    }

    override fun run(
        actor: Actor,
        definition: QueryDefinition,
        parameters: Map<String, Any?>,
        limit: Int,
    ): QueryResult {
        val c = Compilation(actor, definition, parameters, checkOnly = false)
        val sql = c.compile()
        val cap = limit.coerceIn(1, 5000)
        val raw =
            try {
                jdbc.sql("set local statement_timeout = 20000").update()
                jdbc
                    .sql("$sql limit ${cap + 1}")
                    .params(c.params)
                    .query { rs, _ ->
                        val values = c.columns.indices.map { rs.getObject("c$it") }
                        val objects = c.objectAliases.associateWith { rs.getObject("o_$it", UUID::class.java) }
                        values to objects.filterValues { it != null }.mapValues { it.value!! }
                    }.list()
            } catch (e: DataAccessException) {
                if (e.mostSpecificCause.message?.contains("statement timeout") == true) {
                    throw ValidationException("QUERY_TOO_SLOW", "The report took too long: narrow it with conditions")
                }
                throw e
            }
        val labels = Labeller(actor)
        var rows =
            raw.take(cap).map { (values, objects) ->
                ResultRow(values.mapIndexed { k, v -> labels.show(c.columns[k].spec, v) }, objects)
            }
        // Ids sort by id in SQL; by what's shown here.
        c.definition.sort.firstOrNull()?.let { s ->
            val type = c.columns.getOrNull(s.column)?.type
            if (type == FieldType.TYPE || type == FieldType.CONTEXT || type == FieldType.PRINCIPAL) {
                val by = compareBy<ResultRow, String?>(nullsLast()) { it.values[s.column] as String? }
                rows = rows.sortedWith(if (s.descending) by.reversed() else by)
            }
        }
        return QueryResult(c.columns.map { it.result }, rows, raw.size > cap)
    }

    /** Shows ids by name, timestamps as instants. */
    private inner class Labeller(
        private val actor: Actor,
    ) {
        private val typeNames by lazy { types.list(actor).associate { it.id.value to it.displayName } }
        private val contextNames = mutableMapOf<UUID, String>()
        private val people = mutableMapOf<String, String>()

        fun show(
            spec: FieldSpec,
            v: Any?,
        ): Any? =
            when {
                v == null -> null
                v is Timestamp -> v.toInstant()
                spec.type == FieldType.TYPE ->
                    (v as? UUID ?: UUID.fromString(v.toString())).let {
                        typeNames[it]
                            ?: it.toString()
                    }
                spec.type == FieldType.CONTEXT -> {
                    val id = v as? UUID ?: UUID.fromString(v.toString())
                    contextNames.getOrPut(
                        id,
                    ) { runCatching { contexts.get(actor, ContextId(id)).name }.getOrDefault(id.toString()) }
                }
                spec.type == FieldType.PRINCIPAL ->
                    people.getOrPut(v.toString()) { directory.displayName(actor.tenant, PrincipalId(v.toString())) }
                spec.type == FieldType.ENUM -> spec.values.firstOrNull { it.key == v.toString() }?.label ?: v.toString()
                v is UUID -> v.toString()
                else -> v
            }
    }

    private class Node(
        val alias: String,
        val index: Int,
        val typeId: UUID?,
        val relation: RelationSpec?,
        val fromNode: Node?,
        val optional: Boolean,
    ) {
        val m = "q${index}m"
        val i = "q${index}i"
        val l = "q${index}l"

        fun sub(template: String): String {
            var s = template.replace("{m}", m).replace("{i}", i).replace("{l}", l)
            if (fromNode != null) s = s.replace("{fm}", fromNode.m).replace("{fi}", fromNode.i)
            return s
        }
    }

    private class Column(
        val sql: String,
        val spec: FieldSpec,
        val type: FieldType,
        val result: ResultColumn,
        val aggregated: Boolean,
        val alias: String,
    )

    /** One query being checked and turned into SQL. */
    private inner class Compilation(
        val actor: Actor,
        val definition: QueryDefinition,
        val values: Map<String, Any?>,
        val checkOnly: Boolean,
    ) {
        val params = mutableMapOf<String, Any?>()
        val columns = mutableListOf<Column>()
        val objectAliases = mutableListOf<String>()
        private val nodes = linkedMapOf<String, Node>()
        private val src = source()
        private val relations = relationSpecs()
        private val typeList by lazy { types.list(actor) }
        private val fieldCache = mutableMapOf<UUID?, Map<String, FieldSpec>>()
        private var counter = 0
        private var conditionCount = 0

        private fun param(value: Any?): String {
            val name = "qp${counter++}"
            params[name] = value
            return ":$name"
        }

        private fun baseClass(typeId: UUID?): String? =
            typeId?.let { id ->
                typeList
                    .firstOrNull {
                        it.id.value == id
                    }?.baseClass
                    ?.name
            }

        private fun descendants(typeId: UUID): List<UUID> {
            val children = typeList.groupBy { it.parentId?.value }
            val out = mutableListOf(typeId)
            var k = 0
            while (k < out.size) {
                children[out[k]].orEmpty().forEach { out += it.id.value }
                k++
            }
            return out
        }

        private fun checkType(typeId: UUID?) {
            if (typeId != null && typeList.none { it.id.value == typeId }) invalid("There's no type $typeId")
        }

        fun compile(): String {
            val d = definition
            if (!ALIAS.matches(d.from.alias)) invalid("Name the objects in lower-case letters: ${d.from.alias}")
            checkType(d.from.typeId)
            nodes[d.from.alias] = Node(d.from.alias, 0, d.from.typeId, null, null, false)
            if (d.links.size > MAX_LINKS) invalid("A report follows at most $MAX_LINKS links")
            d.links.forEachIndexed { k, link ->
                if (!ALIAS.matches(link.alias)) invalid("Name the linked objects in lower-case letters: ${link.alias}")
                if (link.alias in nodes) invalid("Two parts of the report are both named ${link.alias}")
                val from =
                    nodes[link.from]
                        ?: invalid("${link.alias} follows a link from ${link.from}, which isn't named before it")
                val relation = relations[link.relation] ?: invalid("There's no link ${link.relation}")
                checkType(link.typeId)
                baseClass(from.typeId)?.let {
                    if (relation.from.isNotEmpty() &&
                        it !in relation.from
                    ) {
                        invalid("${relation.label} doesn't start from ${it.lowercase()}s")
                    }
                }
                baseClass(link.typeId)?.let {
                    if (relation.to.isNotEmpty() &&
                        it !in relation.to
                    ) {
                        invalid("${relation.label} doesn't lead to ${it.lowercase()}s")
                    }
                }
                // A link that leads to one kind of object: its objects have that kind's attributes.
                val typeId =
                    link.typeId ?: relation.to.singleOrNull()?.let { base ->
                        typeList.firstOrNull { it.parentId == null && it.baseClass.name == base }?.id?.value
                    }
                nodes[link.alias] = Node(link.alias, k + 1, typeId, relation, from, link.optional)
            }
            d.parameters.forEach {
                if (!ALIAS.matches(
                        it.name,
                    )
                ) {
                    invalid("Name the question in lower-case letters: ${it.name}")
                }
            }
            if (d.parameters
                    .map { it.name }
                    .toSet()
                    .size !=
                d.parameters.size
            ) {
                invalid("Two questions have the same name")
            }
            if (d.columns.isEmpty() || d.columns.size > MAX_COLUMNS) invalid("A report shows 1 to $MAX_COLUMNS columns")
            d.columns.forEach { c ->
                val (sql, spec) = field(c.field.alias, c.field.field)
                val agg = c.aggregate
                if (agg != null) {
                    val ok =
                        when (agg) {
                            Aggregate.COUNT, Aggregate.COUNT_DISTINCT -> true
                            Aggregate.SUM, Aggregate.AVG -> spec.type == FieldType.NUMBER
                            Aggregate.MIN, Aggregate.MAX ->
                                spec.type in
                                    setOf(FieldType.NUMBER, FieldType.DATE, FieldType.TEXT)
                        }
                    if (!ok) invalid("${spec.label} can't be totalled with ${agg.name.lowercase()}")
                }
                val expr =
                    when (agg) {
                        null -> sql
                        Aggregate.COUNT -> "count($sql)"
                        Aggregate.COUNT_DISTINCT -> "count(distinct $sql)"
                        Aggregate.SUM -> "sum($sql)"
                        Aggregate.AVG -> "avg($sql)"
                        Aggregate.MIN -> "min($sql)"
                        Aggregate.MAX -> "max($sql)"
                    }
                val type =
                    if (agg == Aggregate.COUNT ||
                        agg == Aggregate.COUNT_DISTINCT
                    ) {
                        FieldType.NUMBER
                    } else {
                        spec.type
                    }
                val label = c.label?.trim()?.ifEmpty { null } ?: spec.label
                columns +=
                    Column(
                        expr,
                        spec,
                        type,
                        ResultColumn(label, type, c.field.alias, c.field.field, agg, spec.unit),
                        agg != null,
                        c.field.alias,
                    )
            }
            d.sort.forEach { if (it.column !in d.columns.indices) invalid("Sorted by a column that isn't there") }
            val where = d.where?.let { condition(it, 0) }
            if (checkOnly) return ""

            val aggregated = columns.any { it.aggregated }
            if (!aggregated) objectAliases += columns.map { it.alias }.distinct()
            val select =
                columns.mapIndexed { k, c -> "${c.sql} as c$k" } +
                    objectAliases.map { "${nodes.getValue(it).m}.id as o_$it" }
            val root = nodes.getValue(d.from.alias)
            val sb = StringBuilder()
            sb.append(if (aggregated) "select " else "select distinct ").append(select.joinToString(", "))
            sb.append("\nfrom ${src.masterTable} ${root.m} join ${src.iterationTable} ${root.i} on ${root.sub(pick())}")
            nodes.values.drop(1).forEach { n -> sb.append('\n').append(join(n)) }
            val filters = mutableListOf(root.sub(src.tenant).replace(":q_tenant", param(actor.tenant.value)))
            filters += own(root)
            if (where != null) filters += where
            sb.append("\nwhere ").append(filters.joinToString("\n  and "))
            if (aggregated) {
                val groups = columns.indices.filter { !columns[it].aggregated }.map { it + 1 }
                if (groups.isNotEmpty()) sb.append("\ngroup by ").append(groups.joinToString(", "))
            }
            val order = d.sort.map { "${it.column + 1} ${if (it.descending) "desc" else "asc"} nulls last" }
            sb.append("\norder by ").append((order.ifEmpty { listOf("1") }).joinToString(", "))
            params["q_state"] = d.versions.state ?: "RELEASED"
            params["q_as_of"] = Timestamp.from(d.versions.asOf ?: clock.instant())
            return sb.toString()
        }

        private fun pick(): String = src.picks[definition.versions.mode] ?: src.picks.getValue(VersionMode.LATEST)

        /** The node's own filters: its type and the reader's access. */
        private fun own(n: Node): List<String> {
            val out = mutableListOf<String>()
            n.typeId?.let {
                out +=
                    "${n.sub(src.typeId)} = any(cast(${param(descendants(it).toTypedArray())} as uuid[]))"
            }
            val p =
                access.filter(
                    actor,
                    Permission.READ,
                    AccessColumns(n.sub(src.contextId), n.sub(src.typeId), n.sub(src.state), n.sub(src.id)),
                )
            params.putAll(p.params)
            out += "(${p.sql})"
            return out
        }

        private fun join(n: Node): String {
            val r = n.relation!!
            val kind = if (n.optional) "left join" else "join"
            val inner =
                if (r.toIteration != null) {
                    "${r.table} ${n.l} join ${src.iterationTable} ${n.i} on ${n.i}.id = ${n.sub(r.toIteration)} " +
                        "join ${src.masterTable} ${n.m} on ${n.m}.id = ${n.sub(
                            src.masterOfIteration,
                        )} and ${n.sub(pick())}"
                } else {
                    "${r.table} ${n.l} join ${src.masterTable} ${n.m} on ${n.m}.id = ${n.sub(
                        r.toMaster ?: error("${r.key}: no target"),
                    )} " +
                        "join ${src.iterationTable} ${n.i} on ${n.sub(pick())}"
                }
            return "$kind ($inner and ${own(n).joinToString(" and ")}) on ${n.sub(r.on)}"
        }

        fun field(
            alias: String,
            key: String,
        ): Pair<String, FieldSpec> {
            val n = nodes[alias] ?: invalid("There's nothing named $alias in the report")
            if (key.startsWith("link:")) {
                val r = n.relation ?: invalid("$alias isn't reached through a link")
                val f =
                    r.fields.firstOrNull { it.key == key.removePrefix("link:") }
                        ?: invalid("${r.label} has no ${key.removePrefix("link:")}")
                return n.sub(f.sql) to f.copy(key = key, label = "${r.label}: ${f.label}")
            }
            val f =
                fieldCache.getOrPut(n.typeId) { objectFields(actor, n.typeId).associateBy { it.key } }[key]
                    ?: invalid("$alias has no field $key")
            return n.sub(f.sql) to f
        }

        private fun condition(
            c: Condition,
            depth: Int,
        ): String? {
            if (depth > 6) invalid("Conditions are nested too deep")
            if (++conditionCount > MAX_CONDITIONS) invalid("A report has at most $MAX_CONDITIONS conditions")
            val group = c.group
            if (group != null) {
                val parts = c.conditions.mapNotNull { condition(it, depth + 1) }
                if (parts.isEmpty()) return null
                return when (group) {
                    GroupOp.AND -> parts.joinToString(" and ", "(", ")")
                    GroupOp.OR -> parts.joinToString(" or ", "(", ")")
                    GroupOp.NOT -> parts.joinToString(" and ", "not (", ")")
                }
            }
            val ref = c.field ?: invalid("A condition needs a field")
            val op = c.operator ?: invalid("A condition needs an operator")
            val (f, spec) = field(ref.alias, ref.field)
            if (op !in
                OPERATORS.getValue(spec.type)
            ) {
                invalid("${spec.label} can't be compared with ${op.name.lowercase()}")
            }
            val noValue = op in setOf(Operator.EMPTY, Operator.NOT_EMPTY, Operator.IS_TRUE, Operator.IS_FALSE)
            val value =
                if (noValue) {
                    null
                } else if (c.parameter != null) {
                    val p =
                        definition.parameters.firstOrNull { it.name == c.parameter }
                            ?: invalid("There's no question ${c.parameter}")
                    val answer = values[p.name]?.takeUnless { it is String && it.isBlank() } ?: p.defaultValue
                    if (answer == null) {
                        if (p.required &&
                            !checkOnly
                        ) {
                            throw ValidationException("QUERY_PARAMETER_MISSING", "Answer: ${p.label}")
                        }
                        return null
                    }
                    answer
                } else {
                    c.value ?: invalid("${spec.label}: compared with what?")
                }
            if (checkOnly) return "true"
            return compare(f, spec, op, value)
        }

        private fun compare(
            f: String,
            spec: FieldSpec,
            op: Operator,
            value: Any?,
        ): String =
            when (op) {
                Operator.EMPTY -> if (spec.type == FieldType.TEXT) "coalesce(($f)::text, '') = ''" else "($f) is null"
                Operator.NOT_EMPTY ->
                    if (spec.type ==
                        FieldType.TEXT
                    ) {
                        "coalesce(($f)::text, '') <> ''"
                    } else {
                        "($f) is not null"
                    }
                Operator.IS_TRUE -> "($f) is true"
                Operator.IS_FALSE -> "($f) is false"
                else ->
                    when (spec.type) {
                        FieldType.NUMBER -> numeric(f, op, value)
                        FieldType.DATE -> date(f, op, value)
                        FieldType.TYPE -> {
                            val ids = list(value).map { uuid(it) }.flatMap { descendants(it) }.distinct()
                            val sql = "($f) = any(cast(${param(ids.toTypedArray())} as uuid[]))"
                            if (op == Operator.NE) "not $sql" else sql
                        }
                        else -> text(f, op, value)
                    }
            }

        private fun text(
            f: String,
            op: Operator,
            value: Any?,
        ): String =
            when (op) {
                Operator.EQ -> "($f)::text = ${param(value.toString())}"
                Operator.NE -> "($f)::text is distinct from ${param(value.toString())}"
                Operator.IN -> "($f)::text = any(cast(${param(
                    list(value).map { it.toString() }.toTypedArray(),
                )} as text[]))"
                Operator.CONTAINS -> "($f)::text ilike ${param("%" + like(value.toString()) + "%")}"
                Operator.STARTS_WITH -> "($f)::text ilike ${param(like(value.toString()) + "%")}"
                else -> invalid("Can't compare text with ${op.name.lowercase()}")
            }

        private fun numeric(
            f: String,
            op: Operator,
            value: Any?,
        ): String {
            if (op == Operator.BETWEEN) {
                val (a, b) = pair(value).let { number(it.first) to number(it.second) }
                return "($f) between ${param(a)} and ${param(b)}"
            }
            val sign =
                mapOf(
                    Operator.EQ to "=",
                    Operator.NE to "<>",
                    Operator.LT to "<",
                    Operator.LE to "<=",
                    Operator.GT to ">",
                    Operator.GE to ">=",
                )[op]
                    ?: invalid("Can't compare numbers with ${op.name.lowercase()}")
            return "($f) $sign ${param(number(value))}"
        }

        private fun date(
            f: String,
            op: Operator,
            value: Any?,
        ): String {
            if (op == Operator.LAST_DAYS) {
                val days = number(value).toLong()
                return "($f) >= ${param(Timestamp.from(clock.instant().minus(Duration.ofDays(days))))}"
            }
            if (op == Operator.BETWEEN) {
                val (a, b) = pair(value)
                return "($f) >= ${param(start(a))} and ($f) < ${param(end(b))}"
            }
            return when (op) {
                Operator.EQ -> "($f) >= ${param(start(value))} and ($f) < ${param(end(value))}"
                Operator.NE -> "not (($f) >= ${param(start(value))} and ($f) < ${param(end(value))})"
                Operator.LT -> "($f) < ${param(start(value))}"
                Operator.LE -> "($f) < ${param(end(value))}"
                Operator.GT -> "($f) >= ${param(end(value))}"
                Operator.GE -> "($f) >= ${param(start(value))}"
                else -> invalid("Can't compare dates with ${op.name.lowercase()}")
            }
        }

        private fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private fun list(value: Any?): List<Any> =
            when (value) {
                is Collection<*> -> value.filterNotNull()
                is Array<*> -> value.filterNotNull()
                null -> emptyList()
                else ->
                    value
                        .toString()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
            }.ifEmpty { invalid("Choose at least one value") }

        private fun pair(value: Any?): Pair<Any, Any> {
            val l = list(value)
            if (l.size != 2) invalid("Between takes two values")
            return l[0] to l[1]
        }

        private fun number(value: Any?): BigDecimal =
            when (value) {
                is BigDecimal -> value
                is Number -> BigDecimal(value.toString())
                else -> value.toString().trim().toBigDecimalOrNull() ?: invalid("$value isn't a number")
            }

        private fun uuid(value: Any): UUID =
            runCatching {
                UUID.fromString(value.toString())
            }.getOrElse { invalid("$value isn't an id") }

        private fun day(value: Any?): Instant {
            val s = value.toString().trim()
            return runCatching { Instant.parse(s) }.getOrNull()
                ?: runCatching { LocalDate.parse(s.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
                ?: invalid("$s isn't a date (yyyy-mm-dd)")
        }

        private fun start(value: Any?) = Timestamp.from(day(value))

        private fun end(value: Any?): Timestamp {
            val s = value.toString().trim()
            val d = day(value)
            return Timestamp.from(if (s.length <= 10) d.plus(Duration.ofDays(1)) else d.plusMillis(1))
        }
    }
}
