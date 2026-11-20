package com.plm.app.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import plm.testing.PostgresTestConfiguration
import tools.jackson.databind.json.JsonMapper

/** Base for tests that drive the API over real HTTP with bearer tokens. Kafka relay is off. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["plm.events.relay.enabled=false"],
)
@Import(PostgresTestConfiguration::class, TestJwtConfiguration::class)
abstract class ApiTest {
    @LocalServerPort
    protected var port: Int = 0

    private val json = JsonMapper.builder().build()

    data class Reply(
        val status: Int,
        val raw: String,
        val body: Map<String, Any?>,
        val list: List<Map<String, Any?>>,
    ) {
        @Suppress("UNCHECKED_CAST")
        fun nested(key: String) = body[key] as Map<String, Any?>
    }

    protected fun call(
        method: HttpMethod,
        path: String,
        as_: TestTokens.User? = TestTokens.alice,
        body: Any? = null,
        token: String? = as_?.let { TestTokens.token(it) },
    ): Reply {
        val request = RestClient.create("http://localhost:$port").method(method).uri(path)
        if (token != null) request.header("Authorization", "Bearer $token")
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body))
        return request.exchange { _, response ->
            val text = response.body.readAllBytes().decodeToString()
            val parsed: Any? = if (text.isBlank()) null else json.readValue(text, Any::class.java)
            @Suppress("UNCHECKED_CAST")
            Reply(
                status = response.statusCode.value(),
                raw = text,
                body = (parsed as? Map<String, Any?>) ?: emptyMap(),
                list = (parsed as? List<Map<String, Any?>>) ?: emptyList(),
            )
        }
    }

    protected fun get(
        path: String,
        as_: TestTokens.User? = TestTokens.alice,
    ) = call(HttpMethod.GET, path, as_)

    protected fun post(
        path: String,
        body: Any? = null,
        as_: TestTokens.User? = TestTokens.alice,
    ) = call(HttpMethod.POST, path, as_, body)

    protected fun put(
        path: String,
        body: Any? = null,
        as_: TestTokens.User? = TestTokens.alice,
    ) = call(HttpMethod.PUT, path, as_, body)
}
