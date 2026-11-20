package com.plm.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import plm.testing.PostgresTestConfiguration
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Drives the versioning engine end to end over HTTP, the way the UI and integrations will. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class)
class VersioningApiIT {
    @LocalServerPort
    private var port: Int = 0

    private val json = JsonMapper.builder().build()

    private data class Reply(
        val status: Int,
        val body: Map<String, Any?>,
        val list: List<Map<String, Any?>> = emptyList(),
    )

    private fun call(
        method: HttpMethod,
        path: String,
        user: String = "alice",
        body: Any? = null,
    ): Reply {
        val request =
            RestClient
                .create("http://localhost:$port")
                .method(method)
                .uri(path)
                .header("X-PLM-User", user)
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body))
        return request.exchange { _, response ->
            val text = response.body.readAllBytes().decodeToString()
            val parsed: Any? = if (text.isBlank()) null else json.readValue(text, Any::class.java)
            @Suppress("UNCHECKED_CAST")
            Reply(
                status = response.statusCode.value(),
                body = (parsed as? Map<String, Any?>) ?: emptyMap(),
                list = (parsed as? List<Map<String, Any?>>) ?: emptyList(),
            )
        }
    }

    private fun post(
        path: String,
        body: Any? = null,
        user: String = "alice",
    ) = call(HttpMethod.POST, path, user, body)

    private fun get(
        path: String,
        user: String = "alice",
    ) = call(HttpMethod.GET, path, user)

    @Suppress("UNCHECKED_CAST")
    private fun Reply.nested(key: String) = body[key] as Map<String, Any?>

    @Test
    fun `full versioning flow over HTTP`() {
        val site = get("/api/v1/contexts/site").body
        val org =
            post(
                "/api/v1/contexts",
                mapOf(
                    "parentId" to site["id"],
                    "kind" to "ORGANIZATION",
                    "name" to "Acme ${UUID.randomUUID()}",
                ),
            )
        assertThat(org.status).isEqualTo(201)
        val product =
            post(
                "/api/v1/contexts",
                mapOf(
                    "parentId" to org.body["id"],
                    "kind" to "PRODUCT",
                    "name" to "Drone",
                ),
            )
        val partType = get("/api/v1/types").list.single { it["name"] == "Part" }

        val created =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to partType["id"],
                    "contextId" to product.body["id"],
                    "name" to "Motor mount",
                    "attributes" to mapOf("material" to "AL6061", "massKg" to 0.12),
                ),
            )
        assertThat(created.status).isEqualTo(201)
        assertThat(created.body["version"]).isEqualTo("A.1")
        val id = created.body["id"]

        val workingCopy = post("/api/v1/objects/$id/checkout")
        assertThat(workingCopy.status).isEqualTo(200)
        assertThat(workingCopy.body["version"]).isEqualTo("A.2")
        assertThat(workingCopy.body["status"]).isEqualTo("WORKING")

        val bobTries = post("/api/v1/objects/$id/checkout", user = "bob")
        assertThat(bobTries.status).isEqualTo(409)
        assertThat(bobTries.body["code"]).isEqualTo("ALREADY_CHECKED_OUT")
        assertThat(bobTries.body["type"]).isEqualTo("urn:plm:error:ALREADY_CHECKED_OUT")
        assertThat(bobTries.nested("details")).containsEntry("checkedOutBy", "alice")

        assertThat(post("/api/v1/objects/$id/checkin", user = "bob").status).isEqualTo(403)

        val patched =
            call(
                HttpMethod.PATCH,
                "/api/v1/objects/$id/working-copy",
                body =
                    mapOf(
                        "attributes" to mapOf("material" to "Ti"),
                    ),
            )
        assertThat(patched.status).isEqualTo(200)

        val checkedIn = post("/api/v1/objects/$id/checkin", mapOf("comment" to "Titanium"))
        assertThat(checkedIn.body["version"]).isEqualTo("A.2")
        assertThat(checkedIn.body["checkedOutBy"]).isNull()
        assertThat(checkedIn.nested("latest")["attributes"]).isEqualTo(mapOf("material" to "Ti"))

        val revised = post("/api/v1/objects/$id/revise", user = "bob")
        assertThat(revised.body["version"]).isEqualTo("B.1")

        val history = get("/api/v1/objects/$id/iterations").list
        assertThat(history.map { it["version"] }).containsExactly("B.1", "A.2", "A.1")
        assertThat(history[1]["comment"]).isEqualTo("Titanium")
    }

    @Test
    fun `errors use problem details`() {
        val missing = get("/api/v1/objects/${UUID.randomUUID()}")
        assertThat(missing.status).isEqualTo(404)
        assertThat(missing.body).containsEntry("code", "OBJECT_NOT_FOUND").containsEntry("status", 404)

        val invalid =
            post(
                "/api/v1/contexts",
                mapOf(
                    "parentId" to get("/api/v1/contexts/site").body["id"],
                    "kind" to "PRODUCT",
                    "name" to "x",
                ),
            )
        assertThat(invalid.status).isEqualTo(422)
        assertThat(invalid.body["code"]).isEqualTo("INVALID_CONTEXT_HIERARCHY")

        val malformed = call(HttpMethod.POST, "/api/v1/objects", body = "not an object")
        assertThat(malformed.status).isEqualTo(400)
    }
}
