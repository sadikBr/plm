package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/** The report builder's queries: links, conditions, totals, questions, and access inside the SQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryApiIT : ApiTest() {
    private val part = "00000000-0000-7000-8000-000000000102"
    private val document = "00000000-0000-7000-8000-000000000101"
    private val s = UUID.randomUUID().toString().take(6)
    private lateinit var a: String

    private fun obj(
        context: Any?,
        type: String,
        name: String,
        mass: Int? = null,
    ): String {
        val attributes = if (mass != null) mapOf("mass" to mapOf("value" to mass, "unit" to "kg")) else emptyMap()
        val r =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to type,
                    "contextId" to context,
                    "name" to name,
                    "number" to "$name-$s",
                    "attributes" to attributes,
                ),
            )
        assertThat(r.status).withFailMessage(r.raw).isEqualTo(201)
        return r.body["id"] as String
    }

    @BeforeAll
    fun data() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Query $s"),
            ).body["id"]
        val p1 = post("/api/v1/contexts", mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Q1 $s")).body["id"]
        val p2 = post("/api/v1/contexts", mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Q2 $s")).body["id"]
        get("/api/v1/me", as_ = TestTokens.bob)
        put("/api/v1/contexts/$p1/members/${TestTokens.bob.sub}", mapOf("role" to "MEMBER"))
        a = obj(p1, part, "QA", 10)
        val b = obj(p1, part, "QB", 2)
        val c = obj(p1, part, "QC", 5)
        val z = obj(p2, part, "QZ", 1)
        val d = obj(p1, document, "QD")
        post("/api/v1/objects/$a/checkout")
        for ((child, qty) in listOf(b to 4, c to 2, z to 1)) {
            post(
                "/api/v1/objects/$a/usages",
                mapOf(
                    "childId" to child,
                    "quantity" to qty,
                ),
            )
        }
        post("/api/v1/objects/$a/checkin", mapOf("comment" to "structure"))
        post("/api/v1/objects/$b/checkout")
        assertThat(
            post("/api/v1/objects/$b/document-links", mapOf("documentId" to d, "kind" to "REFERENCES")).status,
        ).isLessThan(300)
        post("/api/v1/objects/$b/checkin", mapOf("comment" to "doc"))
    }

    private fun run(
        definition: Map<String, Any?>,
        parameters: Map<String, Any?> = emptyMap(),
        as_: TestTokens.User = TestTokens.alice,
    ) = post("/api/v1/query/run", mapOf("definition" to definition, "parameters" to parameters), as_ = as_)

    @Suppress("UNCHECKED_CAST")
    private fun rows(r: Reply): List<List<Any?>> {
        assertThat(r.status).withFailMessage(r.raw).isEqualTo(200)
        return (r.body["rows"] as List<Map<String, Any?>>).map { it["values"] as List<Any?> }
    }

    private fun f(
        alias: String,
        field: String,
    ) = mapOf("alias" to alias, "field" to field)

    private fun cond(
        alias: String,
        field: String,
        operator: String,
        value: Any? = null,
    ) = mapOf("field" to f(alias, field), "operator" to operator, "value" to value)

    private val mine = cond("p", "number", "STARTS_WITH", "Q")
    private val suffix = cond("p", "number", "CONTAINS", "-$s")

    @Test
    fun `attribute conditions, sorted`() {
        val r =
            run(
                mapOf(
                    "from" to mapOf("alias" to "p", "typeId" to part),
                    "where" to mapOf("group" to "AND", "conditions" to listOf(suffix, cond("p", "attr:mass", "GT", 3))),
                    "columns" to listOf(mapOf("field" to f("p", "number")), mapOf("field" to f("p", "attr:mass"))),
                    "sort" to listOf(mapOf("column" to 1, "descending" to true)),
                ),
            )
        assertThat(rows(r)).containsExactly(listOf("QA-$s", 10.0), listOf("QC-$s", 5.0))
    }

    @Test
    fun `structure lines, and what the reader may not see is left out`() {
        val definition =
            mapOf(
                "from" to mapOf("alias" to "p", "typeId" to part),
                "links" to listOf(mapOf("alias" to "child", "from" to "p", "relation" to "uses")),
                "where" to cond("p", "number", "EQ", "QA-$s"),
                "columns" to
                    listOf(mapOf("field" to f("child", "number")), mapOf("field" to f("child", "link:quantity"))),
                "sort" to listOf(mapOf("column" to 0)),
            )
        assertThat(rows(run(definition))).containsExactly(listOf("QB-$s", 4), listOf("QC-$s", 2), listOf("QZ-$s", 1))
        assertThat(rows(run(definition, as_ = TestTokens.bob))).containsExactly(listOf("QB-$s", 4), listOf("QC-$s", 2))
    }

    @Test
    fun `totals per group`() {
        val r =
            run(
                mapOf(
                    "from" to mapOf("alias" to "p", "typeId" to part),
                    "links" to listOf(mapOf("alias" to "child", "from" to "p", "relation" to "uses")),
                    "where" to suffix,
                    "columns" to
                        listOf(
                            mapOf("field" to f("p", "number")),
                            mapOf("field" to f("child", "number"), "aggregate" to "COUNT"),
                            mapOf("field" to f("child", "link:quantity"), "aggregate" to "SUM"),
                            mapOf("field" to f("child", "attr:mass"), "aggregate" to "MAX"),
                        ),
                ),
            )
        assertThat(rows(r)).containsExactly(listOf("QA-$s", 3, 7, 5.0))
    }

    @Test
    fun `or, not, and optional links`() {
        val or =
            mapOf(
                "group" to "AND",
                "conditions" to
                    listOf(
                        suffix,
                        mapOf(
                            "group" to "OR",
                            "conditions" to listOf(cond("p", "name", "EQ", "QB"), cond("p", "attr:mass", "GE", 10)),
                        ),
                        mapOf("group" to "NOT", "conditions" to listOf(cond("p", "name", "EQ", "QA"))),
                    ),
            )
        val r =
            run(
                mapOf(
                    "from" to mapOf("alias" to "p", "typeId" to part),
                    "links" to
                        listOf(mapOf("alias" to "doc", "from" to "p", "relation" to "documents", "optional" to true)),
                    "where" to or,
                    "columns" to listOf(mapOf("field" to f("p", "number")), mapOf("field" to f("doc", "number"))),
                ),
            )
        assertThat(rows(r)).containsExactly(listOf("QB-$s", "QD-$s"))
        val all =
            run(
                mapOf(
                    "from" to mapOf("alias" to "p", "typeId" to part),
                    "links" to
                        listOf(mapOf("alias" to "doc", "from" to "p", "relation" to "documents", "optional" to true)),
                    "where" to mapOf("group" to "AND", "conditions" to listOf(suffix, mine)),
                    "columns" to listOf(mapOf("field" to f("p", "number")), mapOf("field" to f("doc", "number"))),
                    "sort" to listOf(mapOf("column" to 0)),
                ),
            )
        assertThat(
            rows(all),
        ).containsExactly(listOf("QA-$s", null), listOf("QB-$s", "QD-$s"), listOf("QC-$s", null), listOf("QZ-$s", null))
    }

    @Test
    fun `where used, and objects to open`() {
        val r =
            run(
                mapOf(
                    "from" to mapOf("alias" to "p", "typeId" to part),
                    "links" to listOf(mapOf("alias" to "parent", "from" to "p", "relation" to "usedBy")),
                    "where" to cond("p", "number", "EQ", "QC-$s"),
                    "columns" to listOf(mapOf("field" to f("parent", "number")), mapOf("field" to f("parent", "type"))),
                ),
            )
        assertThat(rows(r)).containsExactly(listOf("QA-$s", "Part"))
        @Suppress("UNCHECKED_CAST")
        val objects = (r.body["rows"] as List<Map<String, Any?>>).first()["objects"] as Map<String, Any?>
        assertThat(objects).containsEntry("parent", a)
    }

    @Test
    fun `questions asked when it runs`() {
        val definition =
            mapOf(
                "from" to mapOf("alias" to "p", "typeId" to part),
                "where" to mapOf("field" to f("p", "number"), "operator" to "EQ", "parameter" to "num"),
                "columns" to listOf(mapOf("field" to f("p", "name"))),
                "parameters" to listOf(mapOf("name" to "num", "label" to "Which number?", "required" to true)),
            )
        assertThat(run(definition).body["code"]).isEqualTo("QUERY_PARAMETER_MISSING")
        assertThat(rows(run(definition, mapOf("num" to "QB-$s")))).containsExactly(listOf("QB"))
    }

    @Test
    fun `bad queries are refused`() {
        val bad =
            run(
                mapOf(
                    "from" to mapOf("alias" to "p", "typeId" to part),
                    "links" to listOf(mapOf("alias" to "x", "from" to "p", "relation" to "nonsense")),
                    "columns" to listOf(mapOf("field" to f("p", "number"))),
                ),
            )
        assertThat(bad.body["code"]).isEqualTo("INVALID_QUERY")
        val injection =
            run(
                mapOf(
                    "from" to mapOf("alias" to "p; drop table x", "typeId" to part),
                    "columns" to listOf(mapOf("field" to f("p", "number"))),
                ),
            )
        assertThat(injection.body["code"]).isEqualTo("INVALID_QUERY")
        val wrongOp =
            run(
                mapOf(
                    "from" to mapOf("alias" to "p"),
                    "where" to cond("p", "number", "GT", 3),
                    "columns" to listOf(mapOf("field" to f("p", "number"))),
                ),
            )
        assertThat(wrongOp.body["code"]).isEqualTo("INVALID_QUERY")
    }

    @Test
    fun `the builder's vocabulary`() {
        assertThat(
            get("/api/v1/query/relations").list.map {
                it["key"]
            },
        ).contains("uses", "usedBy", "documents", "changes", "cadModels")
        assertThat(
            get("/api/v1/query/fields?typeId=$part").list.map {
                it["key"]
            },
        ).contains("number", "state", "attr:mass", "class")
    }

    @Test
    fun `a report runs its query, answers its questions and charts it`() {
        val definition =
            mapOf(
                "from" to mapOf("alias" to "p", "typeId" to part),
                "links" to listOf(mapOf("alias" to "child", "from" to "p", "relation" to "uses")),
                "where" to mapOf("field" to f("p", "number"), "operator" to "EQ", "parameter" to "num"),
                "columns" to
                    listOf(
                        mapOf("field" to f("child", "state")),
                        mapOf("field" to f("child", "link:quantity"), "aggregate" to "SUM"),
                    ),
                "parameters" to listOf(mapOf("name" to "num", "label" to "Assembly", "required" to true)),
            )
        val saved =
            post(
                "/api/v1/reports",
                mapOf(
                    "name" to "Quantities by state $s",
                    "definition" to definition,
                    "groupBy" to "col:0",
                    "answers" to mapOf("num" to "QA-$s"),
                ),
            )
        assertThat(saved.status).withFailMessage(saved.raw).isEqualTo(201)
        val id = saved.body["id"]
        val result = post("/api/v1/reports/$id/run")
        assertThat(result.status).withFailMessage(result.raw).isEqualTo(200)
        assertThat(result.body["total"]).isEqualTo(1)
        @Suppress("UNCHECKED_CAST")
        val groups = result.body["groups"] as List<Map<String, Any?>>
        assertThat(groups).containsExactly(mapOf("key" to "IN_WORK", "count" to 7))
        val other = post("/api/v1/reports/$id/run", mapOf("answers" to mapOf("num" to "QB-$s")))
        assertThat(other.body["total"]).isEqualTo(0)
        val broken =
            post(
                "/api/v1/reports",
                mapOf(
                    "name" to "Broken $s",
                    "definition" to mapOf("columns" to emptyList<Any>()),
                ),
            )
        assertThat(broken.body["code"]).isEqualTo("INVALID_QUERY")
    }
}
