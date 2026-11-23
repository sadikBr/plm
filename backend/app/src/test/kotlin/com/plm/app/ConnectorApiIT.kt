package com.plm.app

import com.plm.app.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.UUID

/** The connector protocol (roadmap 8.5): a manifest saved in one go, files listed to open a model. */
class ConnectorApiIT : ApiTest() {
    private fun upload(
        name: String,
        bytes: ByteArray,
    ): String {
        val ticket = post("/api/v1/vault/uploads", mapOf("fileName" to name, "size" to bytes.size))
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

    @Test
    fun `a manifest saves an assembly and its parts in one go, or nothing`() {
        val s = UUID.randomUUID().toString().take(6)
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Conn $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Conn $s"),
            ).body["id"]
        assertThat(get("/api/v1/connectors").body["protocol"]).isEqualTo(1)

        val manifest =
            mapOf(
                "contextId" to product,
                "application" to "TestCAD 2",
                "comment" to "First",
                "models" to
                    listOf(
                        mapOf(
                            "key" to "Arm.asm",
                            "name" to "Arm $s",
                            "kind" to "ASSEMBLY",
                            "uploadId" to upload("Arm.asm", "assembly".toByteArray()),
                            "dependencies" to
                                listOf(
                                    mapOf("key" to "Pin.prt", "occurrence" to "Pin:1"),
                                    mapOf("key" to "Pin.prt", "occurrence" to "Pin:2"),
                                ),
                            "part" to mapOf("create" to true),
                        ),
                        mapOf(
                            "key" to "Pin.prt",
                            "name" to "Pin $s",
                            "kind" to "PART",
                            "uploadId" to upload("Pin.prt", "pin".toByteArray()),
                        ),
                    ),
            )
        val saved = post("/api/v1/connectors/save", manifest)
        assertThat(saved.status).withFailMessage(saved.raw).isEqualTo(200)
        assertThat(saved.list.map { it["created"] }).containsExactly(true, true)
        val arm = saved.list.first { it["key"] == "Arm.asm" }
        assertThat(arm["version"]).isEqualTo("A.2")
        assertThat(arm["partNumber"]).isNotNull()

        val files = get("/api/v1/connectors/models/${arm["id"]}/files").list
        assertThat(files.map { it["fileName"] }).containsExactly("Arm.asm", "Pin.prt")
        assertThat(
            (files[0]["dependencies"] as List<*>).map {
                (it as Map<*, *>)["occurrence"]
            },
        ).containsExactly("Pin:1", "Pin:2")
        assertThat(files[0]["url"] as String).contains("/api/v1/vault/files")

        // Saving again by id updates; an unknown dependency undoes the whole save.
        val pin = saved.list.first { it["key"] == "Pin.prt" }
        val bad =
            post(
                "/api/v1/connectors/save",
                mapOf(
                    "contextId" to product,
                    "application" to "TestCAD 2",
                    "models" to
                        listOf(
                            mapOf(
                                "key" to "Pin.prt",
                                "id" to pin["id"],
                                "name" to "Pin",
                                "kind" to "PART",
                                "uploadId" to upload("Pin.prt", "pin v2".toByteArray()),
                            ),
                            mapOf(
                                "key" to "New.prt",
                                "name" to "New $s",
                                "kind" to "PART",
                                "dependencies" to listOf(mapOf("key" to "Missing.prt")),
                            ),
                        ),
                ),
            )
        assertThat(bad.body["code"]).isEqualTo("UNKNOWN_DEPENDENCY")
        assertThat(bad.body["detail"] as String).startsWith("New.prt:")
        // Pin wasn't checked out or changed, and New wasn't created.
        assertThat(get("/api/v1/objects/${pin["id"]}").body["checkedOutBy"]).isNull()
        assertThat(get("/api/v1/objects?q=New%20$s").list).isEmpty()
    }
}
