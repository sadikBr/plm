package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Migration from another PLM system (roadmap 9.3): mappings, dry run, history kept, resumable. */
class MigrationApiIT : ApiTest() {
    private val json = JsonMapper.builder().build()
    private val s = UUID.randomUUID().toString().take(6)

    private fun manifest() =
        mapOf(
            "format" to "plm-migration/1",
            "source" to "Legacy PDM 9",
            "users" to listOf(mapOf("id" to "u1", "username" to "jdoe-$s", "displayName" to "John Doe")),
            "objects" to
                listOf(
                    mapOf(
                        "id" to "L1",
                        "type" to "WTPart",
                        "number" to "MIG-100-$s",
                        "name" to "Gearbox",
                        "folder" to "/Legacy/Drivetrain",
                        "iterations" to
                            listOf(
                                it(
                                    "A",
                                    1,
                                    "INWORK",
                                    "2019-03-01T09:00:00Z",
                                    mapOf(
                                        "Description" to "Gearbox, first cut",
                                        "LegacyCode" to "GX",
                                    ),
                                ),
                                it("A", 2, "INWORK", "2019-04-11T15:30:00Z", mapOf("Description" to "Gearbox")),
                                it(
                                    "B",
                                    1,
                                    "RELEASED",
                                    "2020-05-01T08:00:00Z",
                                    mapOf("Description" to "Gearbox, 6-speed"),
                                ) +
                                    mapOf(
                                        "uses" to listOf(mapOf("child" to "L2", "quantity" to 2, "findNumber" to "10")),
                                        "describedBy" to listOf("L3"),
                                    ),
                            ),
                    ),
                    mapOf(
                        "id" to "L2",
                        "type" to "WTPart",
                        "number" to "MIG-101-$s",
                        "name" to "Output shaft",
                        "iterations" to listOf(it("A", 1, "RELEASED", "2019-02-01T10:00:00Z", emptyMap())),
                    ),
                    mapOf(
                        "id" to "L3",
                        "type" to "WTDocument",
                        "number" to "MIG-D1-$s",
                        "name" to "Gearbox drawing",
                        "iterations" to
                            listOf(
                                it("A", 1, "INWORK", "2019-03-02T09:00:00Z", emptyMap()) +
                                    mapOf("files" to listOf(mapOf("path" to "files/gearbox-a1.pdf"))),
                                it("A", 2, "RELEASED", "2020-04-30T09:00:00Z", emptyMap()) +
                                    mapOf("files" to listOf(mapOf("path" to "files/gearbox-a2.pdf"))),
                            ),
                    ),
                ),
        )

    private fun it(
        version: String,
        iteration: Int,
        state: String,
        at: String,
        attributes: Map<String, Any?>,
    ) = mapOf(
        "version" to version,
        "iteration" to iteration,
        "state" to state,
        "createdAt" to at,
        "createdBy" to "u1",
        "comment" to "$version.$iteration in the old system",
        "attributes" to attributes,
    )

    private fun pkg(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(json.writeValueAsBytes(manifest()))
            z.closeEntry()
            for (name in listOf("files/gearbox-a1.pdf", "files/gearbox-a2.pdf")) {
                z.putNextEntry(ZipEntry(name))
                z.write("%PDF-1.4 $name".toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun upload(contextId: String): Map<String, Any?> {
        val parts = LinkedMultiValueMap<String, Any>()
        parts.add(
            "package",
            object : ByteArrayResource(pkg()) {
                override fun getFilename() = "legacy.zip"
            },
        )
        return RestClient
            .create("http://localhost:$port")
            .post()
            .uri("/api/v1/migrations?contextId=$contextId&name=Legacy PDM $s")
            .header("Authorization", "Bearer ${TestTokens.token(TestTokens.alice)}")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(parts)
            .retrieve()
            .body(Map::class.java) as Map<String, Any?>
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
    fun `a package comes in with its history, after mapping what doesn't match`() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Mig $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Mig $s"),
            ).body["id"] as String

        val created = upload(product)
        val id = created["id"]
        val analysis = created["analysis"] as Map<String, Any?>
        assertThat(analysis["objects"]).isEqualTo(3)
        assertThat(analysis["iterations"]).isEqualTo(6)
        assertThat((analysis["unmappedTypes"] as Map<*, *>).keys).containsExactlyInAnyOrder("WTPart", "WTDocument")
        assertThat((analysis["unmappedStates"] as Map<*, *>).keys).contains("INWORK")
        assertThat(analysis["errors"] as Int).isGreaterThan(0)
        assertThat(post("/api/v1/migrations/$id/run").body["code"]).isEqualTo("INVALID_MIGRATION")

        val mapped =
            put(
                "/api/v1/migrations/$id/mappings",
                mapOf(
                    "types" to mapOf("WTPart" to "Part", "WTDocument" to "Document"),
                    "states" to mapOf("INWORK" to "IN_WORK"),
                    "attributes" to mapOf("Description" to "description", "LegacyCode" to ""),
                ),
            ).body
        val ready = mapped["analysis"] as Map<String, Any?>
        assertThat(ready["errors"]).withFailMessage(ready["issues"].toString()).isEqualTo(0)

        val job = await(post("/api/v1/migrations/$id/run").body["id"])
        assertThat(job["status"]).withFailMessage(job.toString()).isEqualTo("SUCCEEDED")
        assertThat((job["result"] as Map<*, *>)["broughtIn"]).isEqualTo(3)
        val items = get("/api/v1/migrations/$id/items").list
        assertThat(items.map { it["status"] }).containsOnly("DONE")

        // History as it was: versions, dates, authors, states, attributes.
        val gearbox = items.first { it["legacyId"] == "L1" }["objectId"]
        val iterations = get("/api/v1/objects/$gearbox/iterations").list
        assertThat(iterations.map { it["version"] }).containsExactlyInAnyOrder("A.1", "A.2", "B.1")
        val first = iterations.first { it["version"] == "A.1" }
        assertThat(first["createdAt"].toString()).startsWith("2019-03-01T09:00")
        assertThat(first["createdBy"]).isEqualTo("jdoe-$s")
        assertThat((first["attributes"] as Map<*, *>)["description"]).isEqualTo("Gearbox, first cut")
        assertThat((first["attributes"] as Map<*, *>).keys).doesNotContain("LegacyCode")
        val obj = get("/api/v1/objects/$gearbox").body
        assertThat(obj["number"]).isEqualTo("MIG-100-$s")
        assertThat((obj["latest"] as Map<*, *>)["state"]).isEqualTo("RELEASED")

        // Structure and links on B.1; each document iteration keeps its own file.
        val lines = get("/api/v1/objects/$gearbox/usages").list
        assertThat(lines.map { (it["child"] as Map<*, *>)["number"] }).containsExactly("MIG-101-$s")
        val doc = items.first { it["legacyId"] == "L3" }["objectId"]
        val docIterations = get("/api/v1/objects/$doc/iterations").list
        val a1 = docIterations.first { it["version"] == "A.1" }["id"]
        assertThat(
            get("/api/v1/objects/$doc/content?iterationId=$a1").list.map {
                it["fileName"]
            },
        ).containsExactly("gearbox-a1.pdf")
        assertThat(get("/api/v1/objects/$doc/content").list.map { it["fileName"] }).containsExactly("gearbox-a2.pdf")

        // Running again brings nothing in twice.
        val again = await(post("/api/v1/migrations/$id/run").body["id"])
        assertThat((again["result"] as Map<*, *>)["broughtIn"]).isEqualTo(0)
        assertThat(get("/api/v1/objects/$gearbox/usages").list).hasSize(1)
    }
}
