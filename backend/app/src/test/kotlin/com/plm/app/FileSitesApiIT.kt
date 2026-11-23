package com.plm.app

import com.plm.app.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.util.UUID

/** File sites and resumable uploads (roadmap 9.10). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileSitesApiIT : ApiTest() {
    private val s = UUID.randomUUID().toString().take(6)
    private val document = "00000000-0000-7000-8000-000000000101"
    private val dir = Files.createTempDirectory("plm-site-")
    private var site: String? = null

    @AfterAll
    fun cleanUp() {
        // Other tests' files shouldn't keep being copied there.
        site?.let { call(HttpMethod.DELETE, "/api/v1/file-sites/$it") }
        put("/api/v1/me/file-site", mapOf("siteId" to null))
    }

    private fun client() = RestClient.create("http://localhost:$port")

    private fun putBytes(
        url: String,
        bytes: ByteArray,
    ): Pair<Int, String> =
        client()
            .put()
            .uri(url)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
            .exchange { _, r -> r.statusCode.value() to r.body.readAllBytes().decodeToString() }

    private fun fetch(url: String) =
        client()
            .get()
            .uri(java.net.URI.create("http://localhost:$port$url"))
            .retrieve()
            .body(ByteArray::class.java)!!

    private fun doc(product: String): String {
        val id =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to document,
                    "contextId" to product,
                    "name" to "Site doc",
                    "number" to "SITE-$s-${UUID.randomUUID().toString().take(4)}",
                ),
            ).body["id"] as String
        post("/api/v1/objects/$id/checkout")
        return id
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `new files are copied to file sites and served from the person's site`() {
        val created = post("/api/v1/file-sites", mapOf("name" to "Shenzhen $s", "location" to dir.toString()))
        assertThat(created.status).withFailMessage(created.raw).isEqualTo(201)
        site = created.body["id"] as String
        assertThat(
            post("/api/v1/file-sites", mapOf("name" to "Bad $s", "location" to "relative/dir")).body["code"],
        ).isEqualTo("INVALID_FILE_SITE")
        put("/api/v1/me/file-site", mapOf("siteId" to site))
        assertThat(
            (get("/api/v1/me/file-site").body["choices"] as List<Map<String, Any?>>).map {
                it["name"]
            },
        ).contains("Shenzhen $s")

        val site0 = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site0, "kind" to "ORGANIZATION", "name" to "Site $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Site $s"),
            ).body["id"] as String
        val id = doc(product)
        val bytes = "Battery tray drawing, rev C".toByteArray()
        val ticket = post("/api/v1/vault/uploads", mapOf("fileName" to "tray.pdf", "size" to bytes.size)).body
        putBytes(ticket["uploadUrl"] as String, bytes)
        post("/api/v1/objects/$id/content", mapOf("uploadId" to ticket["uploadId"], "role" to "PRIMARY"))

        // The copy arrives in the site's directory.
        var copies = 0L
        repeat(80) {
            copies = Files.walk(dir).filter { Files.isRegularFile(it) }.count()
            if (copies > 0) return@repeat
            Thread.sleep(250)
        }
        assertThat(copies).isGreaterThan(0)

        // The download link is the site's, and gives the same bytes.
        val contentId = get("/api/v1/objects/$id/content?workingCopy=true").list.first()["id"]
        var url = ""
        repeat(40) {
            url = get("/api/v1/content/$contentId/link").body["url"] as String
            if (url.contains("&s=")) return@repeat
            Thread.sleep(250)
        }
        assertThat(url).contains("&s=$site")
        assertThat(fetch(url)).isEqualTo(bytes)
        val sites = get("/api/v1/file-sites").list.first { it["id"] == site }
        assertThat((sites["ready"] as Number).toLong()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `a large file goes up in parts, resumably`() {
        val ticket = post("/api/v1/vault/uploads", mapOf("fileName" to "scan.bin")).body
        val parts = ticket["partsUrl"] as String
        putBytes(parts.replace("{n}", "1"), "hello ".toByteArray())
        putBytes(parts.replace("{n}", "3"), "world".toByteArray())
        // The client asks what arrived, and sends what's missing.
        val arrived =
            client().get().uri(ticket["listUrl"] as String).retrieve().body(List::class.java)!!.map {
                (it as Map<*, *>)["part"]
            }
        assertThat(arrived).containsExactly(1, 3)
        val early =
            client().post().uri(ticket["completeUrl"] as String).exchange { _, r ->
                r.statusCode.value() to
                    r.body.readAllBytes().decodeToString()
            }
        assertThat(early.second).contains("PARTS_MISSING")
        putBytes(parts.replace("{n}", "2"), "big ".toByteArray())
        val done =
            client()
                .post()
                .uri(ticket["completeUrl"] as String)
                .retrieve()
                .body(Map::class.java)!!
        assertThat(done["size"]).isEqualTo(15)

        val site0 = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site0, "kind" to "ORGANIZATION", "name" to "Parts $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Parts $s"),
            ).body["id"] as String
        val id = doc(product)
        assertThat(
            post("/api/v1/objects/$id/content", mapOf("uploadId" to ticket["uploadId"], "role" to "PRIMARY")).status,
        ).isEqualTo(201)
        val contentId = get("/api/v1/objects/$id/content?workingCopy=true").list.first()["id"]
        assertThat(
            fetch(get("/api/v1/content/$contentId/link").body["url"] as String).decodeToString(),
        ).isEqualTo("hello big world")
    }
}
