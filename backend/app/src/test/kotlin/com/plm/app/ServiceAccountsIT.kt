package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.Base64
import java.util.UUID

/** Service accounts, API tokens and client credentials (roadmap 2.7), over real HTTP. */
class ServiceAccountsIT : ApiTest() {
    private fun account(siteAdmin: Boolean = false): String {
        val name = "it-" + UUID.randomUUID().toString().take(8)
        val created =
            post(
                "/api/v1/service-accounts",
                mapOf(
                    "name" to name,
                    "displayName" to "Test $name",
                    "siteAdmin" to siteAdmin,
                ),
            )
        assertThat(created.status).isEqualTo(201)
        return created.body["id"] as String
    }

    private fun issue(
        id: String,
        scopes: List<String>,
        kind: String = "API_TOKEN",
    ): Map<String, Any?> {
        val issued =
            post(
                "/api/v1/service-accounts/$id/tokens",
                mapOf(
                    "name" to "ci",
                    "scopes" to scopes,
                    "kind" to kind,
                ),
            )
        assertThat(issued.status).describedAs(issued.raw).isEqualTo(201)
        return issued.body
    }

    private fun secretOf(issued: Map<String, Any?>) = issued["secret"] as String

    private fun tokenCall(
        method: HttpMethod,
        path: String,
        token: String,
        body: Any? = null,
    ) = call(method, path, as_ = null, body = body, token = token)

    private fun oauth(
        form: String,
        basic: String? = null,
    ): Pair<Int, String> {
        val request =
            RestClient
                .create("http://localhost:$port")
                .post()
                .uri("/api/v1/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
        if (basic != null) request.header("Authorization", "Basic $basic")
        return request.exchange { _, r -> r.statusCode.value() to r.body.readAllBytes().decodeToString() }
    }

    @Test
    fun `an API token signs in as its service account, within its scopes`() {
        val id = account()
        val reader = secretOf(issue(id, listOf("read")))

        val me = tokenCall(HttpMethod.GET, "/api/v1/me", reader)
        assertThat(me.status).isEqualTo(200)
        assertThat(me.nested("user")["id"]).isEqualTo(id)
        assertThat(me.body["siteAdmin"]).isEqualTo(false)

        val change = tokenCall(HttpMethod.POST, "/api/v1/types", reader, mapOf("name" to "X"))
        assertThat(change.status).isEqualTo(403)
        assertThat(change.body["code"]).isEqualTo("INSUFFICIENT_SCOPE")

        // Tokens never manage credentials, whatever their scopes.
        val writer = secretOf(issue(id, listOf("read", "write")))
        assertThat(
            tokenCall(HttpMethod.GET, "/api/v1/service-accounts", writer).body["code"],
        ).isEqualTo("INSUFFICIENT_SCOPE")

        assertThat(tokenCall(HttpMethod.GET, "/api/v1/types", "plm_not-a-token").status).isEqualTo(401)
    }

    @Test
    fun `revoked tokens and inactive accounts stop working`() {
        val id = account()
        val issued = issue(id, listOf("read"))
        val token = secretOf(issued)
        assertThat(tokenCall(HttpMethod.GET, "/api/v1/types", token).status).isEqualTo(200)

        @Suppress("UNCHECKED_CAST")
        val tokenId = (issued["token"] as Map<String, Any?>)["id"]
        assertThat(call(HttpMethod.DELETE, "/api/v1/tokens/$tokenId").status).isEqualTo(204)
        assertThat(tokenCall(HttpMethod.GET, "/api/v1/types", token).status).isEqualTo(401)

        val other = secretOf(issue(id, listOf("read")))
        put("/api/v1/service-accounts/$id", mapOf("displayName" to "Off", "active" to false))
        assertThat(tokenCall(HttpMethod.GET, "/api/v1/types", other).status).isEqualTo(401)
        // The list shows the token's prefix and when it was last used, never the token.
        val tokens = get("/api/v1/service-accounts/$id/tokens").list
        assertThat(tokens).allSatisfy { assertThat(it).doesNotContainKey("secret").containsKey("prefix") }
    }

    @Test
    fun `admin scope only for accounts allowed to administer`() {
        val plain = account()
        val refused = post("/api/v1/service-accounts/$plain/tokens", mapOf("name" to "x", "scopes" to listOf("admin")))
        assertThat(refused.body["code"]).isEqualTo("INVALID_TOKEN_REQUEST")

        val admin = account(siteAdmin = true)
        val token = secretOf(issue(admin, listOf("read", "admin")))
        assertThat(tokenCall(HttpMethod.GET, "/api/v1/me", token).body["siteAdmin"]).isEqualTo(true)
        assertThat(tokenCall(HttpMethod.GET, "/api/v1/audit", token).status).isEqualTo(200)

        val noAdminScope = secretOf(issue(admin, listOf("read")))
        assertThat(tokenCall(HttpMethod.GET, "/api/v1/audit", noAdminScope).status).isEqualTo(403)
    }

    @Test
    fun `client credentials give a short-lived access token`() {
        val id = account()
        val secret = secretOf(issue(id, listOf("read"), kind = "CLIENT_SECRET"))
        // A client secret isn't a bearer token itself.
        assertThat(tokenCall(HttpMethod.GET, "/api/v1/types", secret).status).isEqualTo(401)

        val (status, body) = oauth("grant_type=client_credentials&client_id=$id&client_secret=$secret")
        assertThat(status).describedAs(body).isEqualTo(200)
        val answer = json(body)
        assertThat(answer["token_type"]).isEqualTo("Bearer")
        assertThat(answer["expires_in"]).isEqualTo(3600)
        assertThat(answer["scope"]).isEqualTo("read")
        assertThat(tokenCall(HttpMethod.GET, "/api/v1/types", answer["access_token"] as String).status).isEqualTo(200)

        val basic = Base64.getEncoder().encodeToString("$id:$secret".toByteArray())
        assertThat(oauth("grant_type=client_credentials", basic).first).isEqualTo(200)

        val (wrong, error) = oauth("grant_type=client_credentials&client_id=$id&client_secret=plm_wrong")
        assertThat(wrong).isEqualTo(401)
        assertThat(json(error)["error"]).isEqualTo("invalid_client")
        assertThat(json(oauth("grant_type=password&client_id=$id&client_secret=$secret").second)["error"])
            .isEqualTo("unsupported_grant_type")
    }

    @Test
    fun `only site admins manage service accounts`() {
        assertThat(get("/api/v1/service-accounts", as_ = TestTokens.bob).body["code"]).isEqualTo("ADMIN_REQUIRED")
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(text: String) =
        tools.jackson.databind.json.JsonMapper
            .builder()
            .build()
            .readValue(text, Map::class.java) as Map<String, Any?>
}
