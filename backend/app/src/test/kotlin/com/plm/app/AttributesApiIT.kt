package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens.bob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Types, attributes, enumerations and units over HTTP, and how invalid values are reported. */
class AttributesApiIT : ApiTest() {
    private val part = "00000000-0000-7000-8000-000000000102"

    @Test
    fun `everyone can read the out-of-the-box attribute model`() {
        val attributes = get("/api/v1/types/$part/attributes", as_ = bob).list
        assertThat(attributes.map { it["name"] }).startsWith("description", "source", "defaultUnit")
        val source = attributes.single { it["name"] == "source" }
        assertThat(
            source,
        ).containsEntry(
            "dataType",
            "ENUMERATION",
        ).containsEntry("defaultValue", "make")
            .containsEntry("inherited", false)

        val enumeration = get("/api/v1/enumerations/${source["enumerationId"]}", as_ = bob).body
        @Suppress("UNCHECKED_CAST")
        assertThat(
            (enumeration["values"] as List<Map<String, Any?>>).map {
                it["key"]
            },
        ).containsExactly("make", "buy", "buySingleSource")
        assertThat(get("/api/v1/units", as_ = bob).list.map { it["code"] }).contains("mm", "kg", "psi")
    }

    @Test
    fun `admins define attributes on subtypes, which then show as inherited plus own`() {
        val type =
            post(
                "/api/v1/types",
                mapOf(
                    "parentId" to part,
                    "name" to "Spring" +
                        UUID
                            .randomUUID()
                            .toString()
                            .take(6)
                            .replace("-", ""),
                    "displayName" to "Spring",
                ),
            )
        val typeId = type.body["id"]

        val defined =
            post(
                "/api/v1/types/$typeId/attributes",
                mapOf(
                    "name" to "springRate",
                    "dataType" to "REAL",
                    "labels" to mapOf("en" to "Spring rate", "de" to "Federrate"),
                    "required" to true,
                    "minValue" to 0,
                ),
            )
        assertThat(defined.status).isEqualTo(201)
        val attributes = get("/api/v1/types/$typeId/attributes", as_ = bob).list
        assertThat(attributes.last()).containsEntry("name", "springRate").containsEntry("inherited", false)
        assertThat(attributes.first()).containsEntry("inherited", true)

        val denied =
            post(
                "/api/v1/types/$typeId/attributes",
                mapOf(
                    "name" to "x",
                    "dataType" to "STRING",
                    "labels" to mapOf("en" to "X"),
                ),
                as_ = bob,
            )
        assertThat(denied.status).isEqualTo(403)

        // Changing an inherited attribute through the subtype is refused: change it where it's defined.
        val sourceId = attributes.single { it["name"] == "source" }["id"]
        assertThat(
            call(
                org.springframework.http.HttpMethod.PUT,
                "/api/v1/types/$typeId/attributes/$sourceId",
                body =
                    mapOf(
                        "labels" to mapOf("en" to "S"),
                    ),
            ).status,
        ).isEqualTo(404)
    }

    @Test
    fun `invalid values are reported per field`() {
        val site = get("/api/v1/contexts/site").body
        val org =
            post(
                "/api/v1/contexts",
                mapOf(
                    "parentId" to site["id"],
                    "kind" to "ORGANIZATION",
                    "name" to "Org ${UUID.randomUUID()}",
                ),
            )
        val product = post("/api/v1/contexts", mapOf("parentId" to org.body["id"], "kind" to "PRODUCT", "name" to "P"))

        val reply =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to part,
                    "contextId" to product.body["id"],
                    "name" to "Bad",
                    "attributes" to mapOf("endItem" to "yes", "mass" to mapOf("value" to -1, "unit" to "kg")),
                ),
            )

        assertThat(reply.status).isEqualTo(422)
        assertThat(reply.body["code"]).isEqualTo("INVALID_ATTRIBUTES")
        @Suppress("UNCHECKED_CAST")
        val errors =
            (reply.nested("details")["errors"] as List<Map<String, Any?>>).associate {
                it["attribute"] to
                    it["code"]
            }
        assertThat(errors).isEqualTo(mapOf("endItem" to "WRONG_TYPE", "mass" to "OUT_OF_RANGE"))
    }
}
