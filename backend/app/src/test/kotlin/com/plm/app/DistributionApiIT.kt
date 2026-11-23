package com.plm.app

import com.plm.app.support.ApiTest
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** ERP distribution (roadmap 9.5): rules, plant values, signed pushes, fetching, acknowledgements. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DistributionApiIT : ApiTest() {
    private val s = UUID.randomUUID().toString().take(6)
    private val part = "00000000-0000-7000-8000-000000000102"
    private val json = JsonMapper.builder().build()

    /** What the fake ERP received: body and whether the signature checked out. */
    private val received = CopyOnWriteArrayList<Pair<Map<*, *>, Boolean>>()
    private var secret = ""
    private val erp =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/materials") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                val ts = ex.requestHeaders.getFirst("X-PLM-Timestamp")
                val mac =
                    Mac
                        .getInstance(
                            "HmacSHA256",
                        ).apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
                val expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal("$ts.$body".toByteArray()))
                received +=
                    json.readValue(body, Map::class.java) to (ex.requestHeaders.getFirst("X-PLM-Signature") == expected)
                ex.sendResponseHeaders(202, -1)
                ex.close()
            }
            start()
        }

    @AfterAll
    fun stop() = erp.stop(0)

    private fun erpCall(
        path: String,
        key: String,
        body: Any? = null,
    ): Pair<Int, String> =
        RestClient
            .create("http://localhost:$port")
            .post()
            .uri(path)
            .header("X-PLM-Target-Secret", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(json.writeValueAsString(body ?: emptyMap<String, Any>()))
            .exchange { _, r -> r.statusCode.value() to r.body.readAllBytes().decodeToString() }

    private fun status(
        id: String,
        target: Any?,
    ) = get("/api/v1/objects/$id/distribution").list.first { it["targetId"] == target }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `a released part goes to the plant's ERP, which acknowledges it`() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Erp $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Erp $s"),
            ).body["id"] as String

        val created =
            post(
                "/api/v1/distribution/targets",
                mapOf(
                    "name" to "SAP plant 1000 $s",
                    "system" to "SAP S/4HANA",
                    "plant" to "1000",
                    "endpoint" to "http://127.0.0.1:${erp.address.port}/materials",
                    "attributes" to
                        listOf(mapOf("name" to "procurementType", "label" to "Procurement type", "required" to true)),
                ),
            ).body
        val target = created["targetId"]
        secret = created["secret"] as String
        post(
            "/api/v1/distribution/targets/$target/rules",
            mapOf(
                "typeId" to part,
                "contextId" to product,
                "state" to "RELEASED",
            ),
        )

        val id =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to part,
                    "contextId" to product,
                    "name" to "Bracket",
                    "number" to "ERP-$s",
                ),
            ).body["id"] as String
        assertThat(status(id, target)["status"]).isEqualTo("NOT_SENT")
        assertThat(status(id, target)["ruleState"]).isEqualTo("RELEASED")

        // Released without its plant values: held, and the object says what's missing.
        post("/api/v1/objects/$id/state", mapOf("state" to "RELEASED", "override" to true))
        assertThat(status(id, target)["status"]).isEqualTo("ERROR")
        assertThat(status(id, target)["message"].toString()).contains("Procurement type")

        put("/api/v1/objects/$id/distribution/$target/plant-values", mapOf("procurementType" to "F"))
        assertThat(post("/api/v1/objects/$id/distribution/$target/send").body["status"]).isEqualTo("PENDING")
        repeat(60) {
            if (received.isNotEmpty() && status(id, target)["status"] == "SENT") return@repeat
            Thread.sleep(250)
        }
        assertThat(received).hasSize(1)
        val (payload, signed) = received.first()
        assertThat(signed).isTrue()
        assertThat((payload["object"] as Map<*, *>)["number"]).isEqualTo("ERP-$s")
        assertThat((payload["plantValues"] as Map<*, *>)["procurementType"]).isEqualTo("F")
        assertThat(status(id, target)["status"]).isEqualTo("SENT")

        // The ERP answers with its material number.
        val (code, _) = erpCall(payload["acknowledge"] as String, secret, mapOf("ok" to true, "erpKey" to "MAT-4711"))
        assertThat(code).isEqualTo(200)
        val ok = status(id, target)
        assertThat(ok["status"]).isEqualTo("ACKNOWLEDGED")
        assertThat(ok["erpKey"]).isEqualTo("MAT-4711")
        assertThat(erpCall(payload["acknowledge"] as String, "wrong", mapOf("ok" to true)).first).isEqualTo(403)
    }

    @Test
    fun `an ERP without an endpoint fetches its messages and can reject them`() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Pull $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Pull $s"),
            ).body["id"] as String
        val created =
            post(
                "/api/v1/distribution/targets",
                mapOf(
                    "name" to "Odoo Lyon $s",
                    "system" to "Odoo",
                    "plant" to "LYO",
                ),
            ).body
        val target = created["targetId"]
        val key = created["secret"] as String
        val id =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to part,
                    "contextId" to product,
                    "name" to "Hinge",
                    "number" to "PULL-$s",
                ),
            ).body["id"] as String
        post("/api/v1/objects/$id/distribution/$target/send")

        val (code, body) = erpCall("/api/v1/distribution/targets/$target/fetch", key)
        assertThat(code).isEqualTo(200)
        val messages = json.readValue(body, List::class.java)
        assertThat(messages).hasSize(1)
        val message = messages.first() as Map<*, *>
        assertThat(status(id, target)["status"]).isEqualTo("SENT")
        // Fetched once only.
        assertThat(
            json.readValue(erpCall("/api/v1/distribution/targets/$target/fetch", key).second, List::class.java),
        ).isEmpty()

        erpCall(
            "/api/v1/distribution/messages/${message["id"]}/ack",
            key,
            mapOf(
                "ok" to false,
                "message" to "Unit of measure EA unknown",
            ),
        )
        val rejected = status(id, target)
        assertThat(rejected["status"]).isEqualTo("ERROR")
        assertThat(rejected["message"]).isEqualTo("Unit of measure EA unknown")
        assertThat(get("/api/v1/distribution/messages?objectId=$id").list.first()["status"]).isEqualTo("REJECTED")
        assertThat(
            call(HttpMethod.GET, "/api/v1/distribution/targets", as_ = com.plm.app.support.TestTokens.bob).status,
        ).isEqualTo(403)
    }
}
