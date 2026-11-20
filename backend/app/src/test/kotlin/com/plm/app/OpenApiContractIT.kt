package com.plm.app

import com.plm.app.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps `api/openapi.json` (the committed API contract) identical to what the server serves, so
 * every API change shows up as a reviewable diff, and the frontend's generated client never drifts.
 *
 * After changing the API, regenerate the file:
 *
 *     ./gradlew :backend:app:test --tests '*OpenApiContractIT' -Dplm.openapi.update=true
 */
class OpenApiContractIT : ApiTest() {
    private val json =
        JsonMapper
            .builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build()

    @Test
    fun `the committed contract matches the served API`() {
        val served = get("/v3/api-docs", as_ = null)
        assertThat(served.status).isEqualTo(200)

        val actual = canonical(json.readTree(served.raw))
        val file = Path.of(System.getProperty("plm.openapi.file") ?: error("plm.openapi.file not set by the build"))

        if (System.getProperty("plm.openapi.update") == "true") {
            Files.createDirectories(file.parent)
            Files.writeString(file, actual)
            return
        }

        assertThat(Files.exists(file)).describedAs("$file is missing. $HOW_TO_UPDATE").isTrue()
        assertThat(Files.readString(file))
            .describedAs("The API changed but api/openapi.json was not regenerated. $HOW_TO_UPDATE")
            .isEqualTo(actual)
    }

    @Test
    fun `the contract documents the essentials`() {
        val spec = json.readTree(get("/v3/api-docs", as_ = null).raw)

        assertThat(spec.path("paths").propertyNames()).contains("/api/v1/objects/{id}/checkout", "/api/v1/me")
        assertThat(spec.path("paths").propertyNames()).noneMatch { it.startsWith("/actuator") }
        val checkout = spec.path("paths").path("/api/v1/objects/{id}/checkout").path("post")
        assertThat(checkout.path("operationId").asString()).isEqualTo("versionedObjectCheckOut")
        assertThat(checkout.path("parameters").toList().map { it.path("name").asString() }).containsExactly("id")
        assertThat(checkout.path("responses").has("default")).isTrue()
        assertThat(spec.path("components").path("securitySchemes").has("keycloak")).isTrue()
    }

    /** Stable text: sorted keys, no server URL (it contains the random test port), trailing newline. */
    private fun canonical(node: JsonNode): String {
        (node as ObjectNode).remove("servers")
        val sorted = json.readTree(json.writeValueAsString(json.treeToValue(node, Any::class.java)))
        return json.writeValueAsString(sorted) + "\n"
    }

    private companion object {
        const val HOW_TO_UPDATE =
            "Run: ./gradlew :backend:app:test --tests '*OpenApiContractIT' -Dplm.openapi.update=true"
    }
}
