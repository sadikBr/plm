package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.Instant

class SecurityIT : ApiTest() {
    private fun status(token: String?) = call(HttpMethod.GET, "/api/v1/types", as_ = null, token = token).status

    @Test
    fun `the API needs a valid token`() {
        assertThat(status(null)).isEqualTo(401)
        assertThat(status("not-a-jwt")).isEqualTo(401)
        assertThat(status(TestTokens.token(TestTokens.bob, signedByStranger = true))).isEqualTo(401)
        assertThat(status(TestTokens.token(TestTokens.bob, audience = "some-other-api"))).isEqualTo(401)
        assertThat(status(TestTokens.token(TestTokens.bob, expiresAt = Instant.now().minusSeconds(120)))).isEqualTo(401)
        assertThat(status(TestTokens.token(TestTokens.bob))).isEqualTo(200)
    }

    @Test
    fun `me reflects the token and records the user in the directory`() {
        val me = get("/api/v1/me", as_ = TestTokens.bob)

        assertThat(me.status).isEqualTo(200)
        assertThat(
            me.nested("user"),
        ).containsEntry("id", TestTokens.bob.sub).containsEntry("displayName", "Bob Builder")
        assertThat(me.body["siteAdmin"]).isEqualTo(false)
        assertThat(get("/api/v1/me").body["siteAdmin"]).isEqualTo(true)
        assertThat(get("/api/v1/principals?username=bob").body["id"]).isEqualTo(TestTokens.bob.sub)
    }

    @Test
    fun `only site admins define types and read the audit log`() {
        val body =
            mapOf("parentId" to "00000000-0000-7000-8000-000000000102", "name" to "Gear", "displayName" to "Gear")

        assertThat(post("/api/v1/types", body, as_ = TestTokens.bob).body["code"]).isEqualTo("ADMIN_REQUIRED")
        assertThat(get("/api/v1/audit", as_ = TestTokens.bob).status).isEqualTo(403)
        assertThat(get("/api/v1/audit").status).isEqualTo(200)
    }
}
