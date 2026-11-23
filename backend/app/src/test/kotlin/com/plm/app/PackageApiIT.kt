package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Packages and deliveries (roadmap 9.4), and a received package coming back in (9.3). */
class PackageApiIT : ApiTest() {
    private val s = UUID.randomUUID().toString().take(6)
    private val part = "00000000-0000-7000-8000-000000000102"
    private val document = "00000000-0000-7000-8000-000000000101"
    private val json = JsonMapper.builder().build()

    private fun obj(
        type: String,
        number: String,
        name: String,
        product: String,
    ): String {
        val r =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to type,
                    "contextId" to product,
                    "name" to name,
                    "number" to number,
                ),
            )
        assertThat(r.status).withFailMessage(r.raw).isEqualTo(201)
        return r.body["id"] as String
    }

    private fun upload(bytes: ByteArray): String {
        val ticket = post("/api/v1/vault/uploads", mapOf("fileName" to "drawing.pdf", "size" to bytes.size))
        RestClient
            .create("http://localhost:$port")
            .put()
            .uri(ticket.body["uploadUrl"] as String)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
            .retrieve()
            .toBodilessEntity()
        return ticket.body["uploadId"] as String
    }

    private fun await(jobId: Any?): Map<String, Any?> {
        var job = get("/api/v1/jobs/$jobId").body
        repeat(160) {
            if (job["status"] in setOf("SUCCEEDED", "FAILED")) return job
            Thread.sleep(250)
            job = get("/api/v1/jobs/$jobId").body
        }
        return job
    }

    @Suppress("UNCHECKED_CAST")
    private fun importBack(
        zip: ByteArray,
        product: String,
        mappings: Map<String, Any?>,
    ): Map<String, Any?> {
        val parts = LinkedMultiValueMap<String, Any>()
        parts.add(
            "package",
            object : ByteArrayResource(zip) {
                override fun getFilename() = "received.zip"
            },
        )
        val m =
            RestClient
                .create("http://localhost:$port")
                .post()
                .uri("/api/v1/migrations?contextId=$product")
                .header("Authorization", "Bearer ${TestTokens.token(TestTokens.alice)}")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(Map::class.java) as Map<String, Any?>
        val mapped = put("/api/v1/migrations/${m["id"]}/mappings", mappings).body
        assertThat((mapped["analysis"] as Map<*, *>)["errors"]).withFailMessage(mapped.toString()).isEqualTo(0)
        val job = await(post("/api/v1/migrations/${m["id"]}/run").body["id"])
        assertThat(job["status"]).withFailMessage(job.toString()).isEqualTo("SUCCEEDED")
        return job["result"] as Map<String, Any?>
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `a package goes out as an expiring download, and comes back as copies or updates`() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Pkg $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Pkg $s"),
            ).body["id"] as String
        val assembly = obj(part, "PK-A-$s", "Pump assembly", product)
        val impeller = obj(part, "PK-B-$s", "Impeller", product)
        val drawing = obj(document, "PK-D-$s", "Pump drawing", product)
        post("/api/v1/objects/$drawing/checkout")
        post(
            "/api/v1/objects/$drawing/content",
            mapOf(
                "uploadId" to upload("%PDF-1.4 pump".toByteArray()),
                "role" to "PRIMARY",
            ),
        )
        post("/api/v1/objects/$drawing/checkin", mapOf("comment" to "drawing"))
        post("/api/v1/objects/$assembly/checkout")
        post("/api/v1/objects/$assembly/usages", mapOf("childId" to impeller, "quantity" to 3))
        post("/api/v1/objects/$assembly/document-links", mapOf("kind" to "DESCRIBED_BY", "documentId" to drawing))
        post("/api/v1/objects/$assembly/checkin", mapOf("comment" to "structure"))

        val pkg = post("/api/v1/packages", mapOf("name" to "Pump for supplier $s", "rootIds" to listOf(assembly)))
        assertThat(pkg.status).withFailMessage(pkg.raw).isEqualTo(201)
        val members = get("/api/v1/packages/${pkg.body["id"]}/members").list
        assertThat(members.map { it["number"] to it["reason"] })
            .containsExactlyInAnyOrder("PK-A-$s" to "ROOT", "PK-B-$s" to "STRUCTURE", "PK-D-$s" to "DOCUMENT")

        val delivery =
            post(
                "/api/v1/packages/${pkg.body["id"]}/deliveries",
                mapOf(
                    "recipient" to "Acme Pumps",
                    "days" to 7,
                ),
            ).body
        assertThat(delivery["status"]).isEqualTo("PREPARING")
        var ready = delivery
        repeat(80) {
            if (ready["status"] != "PREPARING") return@repeat
            Thread.sleep(250)
            ready = (get("/api/v1/packages/${pkg.body["id"]}").body["deliveries"] as List<Map<String, Any?>>).first()
        }
        assertThat(ready["status"]).withFailMessage(ready.toString()).isEqualTo("READY")
        assertThat(ready["members"]).isEqualTo(3)

        // The recipient needs no account: the link is the key, and the download is logged.
        val zip =
            RestClient
                .create("http://localhost:$port")
                .get()
                .uri(ready["downloadPath"] as String)
                .retrieve()
                .body(ByteArray::class.java)!!
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            generateSequence { z.nextEntry }.forEach {
                entries[it.name] =
                    z.readAllBytes()
            }
        }
        assertThat(entries.keys).contains("manifest.json", "files/PK-D-$s/A.2/drawing.pdf")
        val manifest = json.readValue(entries.getValue("manifest.json"), Map::class.java)
        assertThat(manifest["format"]).isEqualTo("plm-migration/1")
        assertThat(get("/api/v1/deliveries/${ready["id"]}/downloads").list).hasSize(1)

        // Back in elsewhere as copies marked with where they came from...
        val other =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Pkg copy $s"),
            ).body["id"] as String
        val copies = importBack(zip, other, mapOf("existing" to "COPY", "copySuffix" to "ACME"))
        assertThat(copies["broughtIn"]).isEqualTo(3)
        val copy = get("/api/v1/objects?contextId=$other&limit=10").list.first { it["number"] == "PK-A-$s-ACME" }
        val copyLines = get("/api/v1/objects/${copy["id"]}/usages").list
        assertThat(copyLines.map { (it["child"] as Map<*, *>)["number"] }).containsExactly("PK-B-$s-ACME")
        assertThat(
            (copy["latest"] as Map<*, *>)["comment"].toString(),
        ).startsWith("Received from Package 'Pump for supplier $s'")

        // ...or as updates: a new iteration of each, structure not doubled.
        val updates = importBack(zip, product, mapOf("existing" to "UPDATE"))
        assertThat(updates["broughtIn"]).isEqualTo(3)
        assertThat(
            get("/api/v1/objects/$assembly").body["latest"].let { (it as Map<*, *>)["version"] },
        ).isEqualTo("A.3")
        assertThat(get("/api/v1/objects/$assembly/usages").list).hasSize(1)

        // Withdrawn: the link stops working.
        post("/api/v1/deliveries/${ready["id"]}/revoke")
        val gone = call(HttpMethod.GET, ready["downloadPath"] as String, as_ = null)
        assertThat(gone.body["code"]).isEqualTo("DELIVERY_UNAVAILABLE")
    }
}
