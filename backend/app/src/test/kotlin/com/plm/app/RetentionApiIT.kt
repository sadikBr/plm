package com.plm.app

import com.plm.app.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.UUID

/** Retention, legal hold, archive and purge (roadmap 9.8). */
class RetentionApiIT : ApiTest() {
    private val s =
        UUID
            .randomUUID()
            .toString()
            .take(6)
            .replace("-", "")
    private val document = "00000000-0000-7000-8000-000000000101"

    private fun subtype(name: String) =
        post(
            "/api/v1/types",
            mapOf("parentId" to document, "name" to "$name$s", "displayName" to "$name $s"),
        ).body["id"] as String

    private fun upload(text: String): String {
        val bytes = text.toByteArray()
        val ticket = post("/api/v1/vault/uploads", mapOf("fileName" to "spec.txt", "size" to bytes.size))
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

    /** A document with [iterations] iterations, each with its own file. */
    private fun doc(
        type: String,
        product: String,
        number: String,
        iterations: Int,
    ): String {
        val id =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to type,
                    "contextId" to product,
                    "name" to "Spec $number",
                    "number" to number,
                ),
            ).body["id"] as String
        repeat(iterations) { n ->
            post("/api/v1/objects/$id/checkout")
            post(
                "/api/v1/objects/$id/content",
                mapOf(
                    "uploadId" to upload("$number issue ${n + 2}"),
                    "role" to "PRIMARY",
                ),
            )
            post("/api/v1/objects/$id/checkin", mapOf("comment" to "issue ${n + 2}"))
        }
        return id
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

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `old iterations are purged, held objects stay put, archived ones come back as they were`() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Ret $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Ret $s"),
            ).body["id"] as String
        val purgeType = subtype("TestReport")
        val archiveType = subtype("OldMinutes")
        val purged = doc(purgeType, product, "RET-P-$s", 3)
        val held = doc(archiveType, product, "RET-H-$s", 1)
        val archived = doc(archiveType, product, "RET-A-$s", 2)
        assertThat(
            get("/api/v1/objects/$purged/iterations").list.map {
                it["version"]
            },
        ).containsExactlyInAnyOrder("A.1", "A.2", "A.3", "A.4")

        post(
            "/api/v1/retention/rules",
            mapOf(
                "name" to "Test reports: keep the last iteration",
                "typeId" to purgeType,
                "ageDays" to 0,
                "action" to "PURGE_ITERATIONS",
            ),
        )
        post(
            "/api/v1/retention/rules",
            mapOf(
                "name" to "Minutes: archive",
                "typeId" to archiveType,
                "ageDays" to 0,
                "action" to "ARCHIVE",
            ),
        )
        val hold =
            post(
                "/api/v1/legal-holds",
                mapOf(
                    "name" to "Case 2026-118 $s",
                    "reason" to "Litigation",
                    "objectIds" to listOf(held),
                ),
            ).body
        assertThat(get("/api/v1/objects/$held/legal-holds").list).hasSize(1)

        // The dry run says what would happen, and why not where not.
        val preview = get("/api/v1/retention/preview").list.filter { it["number"].toString().endsWith(s) }
        assertThat(
            preview.first {
                it["number"] == "RET-P-$s"
            },
        ).containsEntry("action", "PURGE_ITERATIONS").containsEntry("detail", "A.1, A.2, A.3")
        assertThat(preview.first { it["number"] == "RET-H-$s" }["action"]).isEqualTo("SKIP")
        assertThat(preview.first { it["number"] == "RET-A-$s" }["action"]).isEqualTo("ARCHIVE")

        // A hold also stops deletion.
        val delete = call(HttpMethod.DELETE, "/api/v1/objects/$held")
        assertThat(delete.raw).contains("legal hold")

        val job = await(post("/api/v1/retention/run").body["id"])
        assertThat(job["status"]).withFailMessage(job.toString()).isEqualTo("SUCCEEDED")
        val results =
            ((job["result"] as Map<*, *>)["results"] as List<Map<*, *>>).filter {
                it["number"].toString().endsWith(s)
            }
        assertThat(results.filter { it["ok"] == false }).withFailMessage(results.toString()).isEmpty()

        // Only the last iteration of the version stays, with its own file.
        assertThat(get("/api/v1/objects/$purged/iterations").list.map { it["version"] }).containsExactly("A.4")
        assertThat(get("/api/v1/objects/$purged/content").list.map { it["fileName"] }).containsExactly("spec.txt")
        // The held one is untouched; the archived one is out of the system.
        assertThat(get("/api/v1/objects/$held").status).isEqualTo(200)
        assertThat(get("/api/v1/objects/$archived").status).isEqualTo(404)
        val entry = get("/api/v1/archives").list.first { it["number"] == "RET-A-$s" }
        assertThat(entry["iterations"]).isEqualTo(3)

        // Restored as it was: number, every iteration, the files.
        val restored = post("/api/v1/archives/${entry["id"]}/restore").body["objectId"]
        assertThat(get("/api/v1/objects/$restored").body["number"]).isEqualTo("RET-A-$s")
        assertThat(
            get("/api/v1/objects/$restored/iterations").list.map {
                it["version"]
            },
        ).containsExactlyInAnyOrder("A.1", "A.2", "A.3")
        assertThat(get("/api/v1/objects/$restored/content").list.map { it["fileName"] }).containsExactly("spec.txt")
        assertThat(post("/api/v1/archives/${entry["id"]}/restore").body["code"]).isEqualTo("ALREADY_RESTORED")

        // Released, the hold no longer protects it.
        post("/api/v1/legal-holds/${hold["id"]}/release")
        assertThat(get("/api/v1/objects/$held/legal-holds").list).isEmpty()
    }
}
