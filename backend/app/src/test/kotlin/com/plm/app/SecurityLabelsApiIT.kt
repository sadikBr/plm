package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/** Security labels and agreements (roadmap 9.7), in every check and list filter. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityLabelsApiIT : ApiTest() {
    private val s =
        UUID
            .randomUUID()
            .toString()
            .take(6)
            .uppercase()
            .filter { it.isLetter() }
            .ifEmpty { "X" }
    private val part = "00000000-0000-7000-8000-000000000102"
    private val bob = TestTokens.bob
    private lateinit var product: String
    private lateinit var label: String
    private lateinit var secret: String
    private lateinit var open: String

    @BeforeAll
    fun data() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf(
                    "parentId" to site,
                    "kind" to "ORGANIZATION",
                    "name" to "Sec $s ${UUID.randomUUID()}",
                ),
            ).body["id"]
        product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Sec $s"),
            ).body["id"] as String
        get("/api/v1/me", as_ = bob)
        put("/api/v1/contexts/$product/members/${bob.sub}", mapOf("role" to "MEMBER"))
        val created =
            post(
                "/api/v1/security-labels",
                mapOf(
                    "key" to "EXPORT_$s",
                    "name" to "Export control $s",
                    "values" to
                        listOf(
                            mapOf("key" to "EAR99", "label" to "EAR99"),
                            mapOf("key" to "ECCN_5A992", "label" to "ECCN 5A992"),
                            mapOf("key" to "ITAR", "label" to "ITAR"),
                        ),
                ),
            )
        assertThat(created.status).withFailMessage(created.raw).isEqualTo(201)
        label = created.body["id"] as String
        secret =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to part,
                    "contextId" to product,
                    "name" to "Guidance housing",
                    "number" to "SEC-1-$s",
                ),
            ).body["id"] as String
        open =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to part,
                    "contextId" to product,
                    "name" to "Bracket",
                    "number" to "SEC-2-$s",
                ),
            ).body["id"] as String
        val marked = put("/api/v1/objects/$secret/security-labels", mapOf("values" to mapOf(label to "ITAR")))
        assertThat(marked.status).withFailMessage(marked.raw).isEqualTo(200)
    }

    private fun visible() = get("/api/v1/objects?contextId=$product&limit=50", as_ = bob).list.map { it["number"] }

    @Test
    fun `a label keeps people out until they are cleared or covered by an agreement`() {
        // Bob is on the team, but not cleared: the object is hidden everywhere.
        val denied = get("/api/v1/objects/$secret", as_ = bob)
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied.body["code"]).isEqualTo("LABEL_CLEARANCE_REQUIRED")
        assertThat(visible()).contains("SEC-2-$s").doesNotContain("SEC-1-$s")
        assertThat(get("/api/v1/objects/$secret/content", as_ = bob).status).isEqualTo(403)

        // He is told why.
        val why = get("/api/v1/objects/$secret/access-explanation", as_ = bob).body
        assertThat(why["blocked"]).isEqualTo(true)
        @Suppress("UNCHECKED_CAST")
        val check = (why["checks"] as List<Map<String, Any?>>).single()
        assertThat(check["value"]).isEqualTo("ITAR")

        // A lower clearance isn't enough; ITAR is.
        post("/api/v1/clearances", mapOf("principalId" to bob.sub, "labelId" to label, "valueKey" to "ECCN_5A992"))
        assertThat(get("/api/v1/objects/$secret", as_ = bob).status).isEqualTo(403)
        post("/api/v1/clearances", mapOf("principalId" to bob.sub, "labelId" to label, "valueKey" to "ITAR"))
        assertThat(get("/api/v1/objects/$secret", as_ = bob).status).isEqualTo(200)
        assertThat(visible()).contains("SEC-1-$s")
        call(org.springframework.http.HttpMethod.DELETE, "/api/v1/clearances/${bob.sub}/$label")
        assertThat(get("/api/v1/objects/$secret", as_ = bob).status).isEqualTo(403)

        // An expired agreement doesn't help; a current one for the product does.
        val past = Instant.now().minusSeconds(86_400 * 30)
        post(
            "/api/v1/agreements",
            mapOf(
                "name" to "TAA-2024 old",
                "labelId" to label,
                "valueKey" to "ITAR",
                "validFrom" to past.toString(),
                "validTo" to past.plusSeconds(86_400).toString(),
                "principals" to listOf(bob.sub),
                "contextIds" to listOf(product),
            ),
        )
        assertThat(get("/api/v1/objects/$secret", as_ = bob).status).isEqualTo(403)
        val agreement =
            post(
                "/api/v1/agreements",
                mapOf(
                    "name" to "TAA-2026-14",
                    "labelId" to label,
                    "valueKey" to "ITAR",
                    "principals" to listOf(bob.sub),
                    "contextIds" to listOf(product),
                ),
            )
        assertThat(agreement.status).withFailMessage(agreement.raw).isEqualTo(201)
        assertThat(get("/api/v1/objects/$secret", as_ = bob).status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val through =
            (
                get(
                    "/api/v1/objects/$secret/access-explanation",
                    as_ = bob,
                ).body["checks"] as List<Map<String, Any?>>
            ).single()
        assertThat(through["through"]).isEqualTo("TAA-2026-14")

        // Only managers label objects; only administrators keep the lists.
        assertThat(
            put("/api/v1/objects/$open/security-labels", mapOf("values" to mapOf(label to "EAR99")), as_ = bob).status,
        ).isEqualTo(403)
        assertThat(
            post(
                "/api/v1/clearances",
                mapOf("principalId" to bob.sub, "labelId" to label, "valueKey" to "ITAR"),
                as_ = bob,
            ).body["code"],
        ).isEqualTo("ADMIN_REQUIRED")
        // Values only ever grow at the end: objects and clearances keep their meaning.
        assertThat(
            put(
                "/api/v1/security-labels/$label",
                mapOf(
                    "key" to "EXPORT_$s",
                    "name" to "Export control",
                    "values" to listOf(mapOf("key" to "ITAR", "label" to "ITAR")),
                ),
            ).body["code"],
        ).isEqualTo("INVALID_SECURITY_LABEL")
    }
}
