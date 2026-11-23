package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Standards (roadmap 9.6): STEP AP242 export, OSLC services, external links. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StandardsApiIT : ApiTest() {
    private val s = UUID.randomUUID().toString().take(6)
    private val part = "00000000-0000-7000-8000-000000000102"
    private val document = "00000000-0000-7000-8000-000000000101"
    private lateinit var product: String
    private lateinit var assembly: String

    private fun client() = RestClient.create("http://localhost:$port")

    private fun obj(
        type: String,
        number: String,
        name: String,
    ) = post(
        "/api/v1/objects",
        mapOf("typeId" to type, "contextId" to product, "name" to name, "number" to number),
    ).body["id"] as String

    @BeforeAll
    fun data() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Std $s"),
            ).body["id"]
        product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Gearbox $s"),
            ).body["id"] as String
        assembly = obj(part, "STD-A-$s", "Gearbox, 6-speed")
        val shaft = obj(part, "STD-S-$s", "Output shaft")
        val model = obj(document, "STD-M-$s", "Gearbox model")
        val bytes = "ISO-10303-21;\nEND-ISO-10303-21;\n".toByteArray()
        val ticket = post("/api/v1/vault/uploads", mapOf("fileName" to "gearbox.stp", "size" to bytes.size))
        client()
            .put()
            .uri(
                ticket.body["uploadUrl"] as String,
            ).contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
            .retrieve()
            .toBodilessEntity()
        post("/api/v1/objects/$model/checkout")
        post("/api/v1/objects/$model/content", mapOf("uploadId" to ticket.body["uploadId"], "role" to "PRIMARY"))
        post("/api/v1/objects/$model/checkin")
        post("/api/v1/objects/$assembly/checkout")
        post("/api/v1/objects/$assembly/usages", mapOf("childId" to shaft, "quantity" to 2, "findNumber" to "10"))
        post("/api/v1/objects/$assembly/document-links", mapOf("kind" to "DESCRIBED_BY", "documentId" to model))
        post("/api/v1/objects/$assembly/checkin")
    }

    @Test
    fun `a structure exports as STEP AP242 that a STEP reader understands, with its CAD files`() {
        val zip =
            client()
                .get()
                .uri("/api/v1/objects/$assembly/step-export")
                .header("Authorization", "Bearer ${TestTokens.token(TestTokens.alice)}")
                .retrieve()
                .body(ByteArray::class.java)!!
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            generateSequence { z.nextEntry }.forEach {
                entries[it.name] =
                    z.readAllBytes()
            }
        }
        assertThat(entries.keys).containsExactlyInAnyOrder("STD-A-$s.stp", "cad/STD-M-$s/gearbox.stp")
        val step = entries.getValue("STD-A-$s.stp").decodeToString()
        assertThat(step).startsWith("ISO-10303-21;").contains("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF")
        assertThat(step).contains("APPLIED_DOCUMENT_REFERENCE").contains("'cad/STD-M-$s/gearbox.stp'")

        // The system's own STEP reader (roadmap 6.5) reads it back: two products, the shaft used twice.
        @Suppress("UNCHECKED_CAST")
        val preview =
            client()
                .post()
                .uri("/api/v1/cad/step-import/preview?fileName=export.stp")
                .header("Authorization", "Bearer ${TestTokens.token(TestTokens.alice)}")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(step.toByteArray())
                .retrieve()
                .body(Map::class.java) as Map<String, Any?>
        assertThat(preview["models"]).isEqualTo(2)
        assertThat(preview["schema"].toString()).contains("AP242")
        assertThat((preview["lines"] as List<*>).size).isGreaterThanOrEqualTo(2)
    }

    private fun oslc(
        path: String,
        accept: String = "application/rdf+xml",
    ): Pair<String?, String> =
        client()
            .get()
            .uri(path)
            .header("Authorization", "Bearer ${TestTokens.token(TestTokens.alice)}")
            .header("Accept", accept)
            .exchange { _, r -> r.headers.contentType?.toString() to r.body.readAllBytes().decodeToString() }

    @Test
    fun `OSLC tools find the catalog, query a provider and read resources and their links`() {
        val (type, catalog) = oslc("/api/v1/oslc/catalog")
        assertThat(type).startsWith("application/rdf+xml")
        assertThat(
            catalog,
        ).contains("core#ServiceProviderCatalog").contains("<oslc:serviceProvider>").contains("core#ServiceProvider\"")

        val (_, provider) = oslc("/api/v1/oslc/contexts/$product/provider", "text/turtle")
        assertThat(
            provider,
        ).contains("oslc:queryCapability").contains("oslc:selectionDialog").contains("oslc:domain oslc_am:")

        val (_, hits) =
            oslc(
                "/api/v1/oslc/contexts/$product/query?oslc.where=dcterms:identifier=\"STD-A-$s\"",
                "application/ld+json",
            )
        assertThat(hits).contains("\"oslc:totalCount\": \"1\"").contains("/api/v1/oslc/resources/$assembly")

        // A requirement in another tool, linked to the gearbox.
        val link =
            post(
                "/api/v1/objects/$assembly/external-links",
                mapOf(
                    "linkType" to "oslc_rm:implementsRequirement",
                    "url" to "https://rm.example.com/req/REQ-118",
                    "title" to "REQ-118 Shift time",
                ),
            )
        assertThat(link.status).withFailMessage(link.raw).isEqualTo(201)
        assertThat(
            post(
                "/api/v1/objects/$assembly/external-links",
                mapOf("linkType" to "nope", "url" to "ftp://x"),
            ).body["code"],
        ).isEqualTo("INVALID_EXTERNAL_LINK")

        val (_, resource) = oslc("/api/v1/oslc/resources/$assembly")
        assertThat(resource)
            .contains("<dcterms:identifier>STD-A-$s</dcterms:identifier>")
            .contains("<oslc_rm:implementsRequirement rdf:resource=\"https://rm.example.com/req/REQ-118\"/>")
            .contains("am#Resource")
        val (compactType, compact) = oslc("/api/v1/oslc/resources/$assembly", "application/x-oslc-compact+xml")
        assertThat(compactType).startsWith("application/x-oslc-compact+xml")
        assertThat(compact).contains("oslc:smallPreview").contains("/oslc/preview/$assembly")
        assertThat(get("/api/v1/objects/$assembly/external-links").list).hasSize(1)
    }
}
