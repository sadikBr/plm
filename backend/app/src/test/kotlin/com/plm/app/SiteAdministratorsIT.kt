package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SiteAdministratorsIT : ApiTest() {
    @Test
    fun `site administrators are listed, the built-in one first, with a warning when there are more`() {
        get("/api/v1/me", as_ = TestTokens.plmadmin)
        get("/api/v1/me", as_ = TestTokens.bob)
        get("/api/v1/me")

        val res = get("/api/v1/site-administrators", as_ = TestTokens.plmadmin)

        assertThat(res.status).isEqualTo(200)
        assertThat(res.body["builtInUsername"]).isEqualTo("plmadmin")
        assertThat(res.body["enforced"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        val admins = res.body["administrators"] as List<Map<String, Any?>>
        assertThat(admins.first()).containsEntry("username", "plmadmin").containsEntry("builtIn", true)
        assertThat(admins.map { it["username"] }).contains("alice").doesNotContain("bob")
        assertThat(res.body["moreThanRecommended"]).isEqualTo(true)
    }

    @Test
    fun `only site administrators see the list`() {
        assertThat(get("/api/v1/site-administrators", as_ = TestTokens.bob).body["code"]).isEqualTo("ADMIN_REQUIRED")
    }
}
