package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import java.util.UUID

/** SCIM 2.0 (roadmap 9.1), as an identity provider drives it: users, groups, shared teams. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScimApiIT : ApiTest() {
    private val s = UUID.randomUUID().toString().take(6)
    private lateinit var token: String
    private lateinit var product: String

    private fun scim(
        method: HttpMethod,
        path: String,
        body: Any? = null,
    ) = call(method, "/api/v1/scim/v2$path", as_ = null, body = body, token = token)

    private fun patch(vararg ops: Map<String, Any?>) =
        mapOf(
            "schemas" to listOf("urn:ietf:params:scim:api:messages:2.0:PatchOp"),
            "Operations" to ops.toList(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun team(group: String): Map<String, Any?> =
        get("/api/v1/shared-teams").list.first { it["directoryGroup"] == group }

    @Suppress("UNCHECKED_CAST")
    private fun memberIds(group: String) = (team(group)["members"] as List<Map<String, Any?>>).map { it["principalId"] }

    private fun user(name: String): Map<String, Any?> =
        mapOf(
            "schemas" to listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
            "userName" to "$name-$s",
            "externalId" to "ext-$name-$s",
            "name" to mapOf("givenName" to name.replaceFirstChar { it.uppercase() }, "familyName" to "Tester"),
            "emails" to listOf(mapOf("value" to "$name-$s@example.com", "type" to "work", "primary" to true)),
            "active" to true,
        )

    @BeforeAll
    fun setUp() {
        val account =
            post(
                "/api/v1/service-accounts",
                mapOf(
                    "name" to "scim-$s",
                    "displayName" to "Directory $s",
                    "siteAdmin" to true,
                ),
            )
        val issued =
            post(
                "/api/v1/service-accounts/${account.body["id"]}/tokens",
                mapOf("name" to "idp", "scopes" to listOf("read", "write", "admin"), "kind" to "API_TOKEN"),
            )
        token = issued.body["secret"] as String
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "SCIM $s"),
            ).body["id"]
        product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "SCIM $s"),
            ).body["id"] as String
    }

    @Test
    fun `users and groups become people and a directory-managed shared team`() {
        assertThat(scim(HttpMethod.GET, "/ServiceProviderConfig").body.nestedBool("patch")).isTrue()

        val dora = scim(HttpMethod.POST, "/Users", user("dora"))
        assertThat(dora.status).withFailMessage(dora.raw).isEqualTo(201)
        val doraId = dora.body["id"] as String
        assertThat(dora.body["displayName"]).isEqualTo("Dora Tester")
        val eli = scim(HttpMethod.POST, "/Users", user("eli")).body["id"] as String

        // The same userName twice: SCIM's uniqueness error, in SCIM's format.
        val again = scim(HttpMethod.POST, "/Users", user("dora"))
        assertThat(again.status).isEqualTo(409)
        assertThat(again.body["scimType"]).isEqualTo("uniqueness")

        // Providers look users up by filter before creating them.
        val found = scim(HttpMethod.GET, "/Users?filter=userName eq \"DORA-$s\"")
        assertThat(found.body["totalResults"]).isEqualTo(1)
        assertThat(scim(HttpMethod.GET, "/Users?filter=bogus").status).isEqualTo(400)

        val group =
            scim(
                HttpMethod.POST,
                "/Groups",
                mapOf("displayName" to "Engineering $s", "members" to listOf(mapOf("value" to doraId))),
            )
        assertThat(group.status).withFailMessage(group.raw).isEqualTo(201)
        val groupId = group.body["id"] as String
        // A person made by the directory has an id before they sign in: the provider's id for them.
        assertThat(memberIds(groupId)).containsExactly("ext-dora-$s")
        assertThat(team(groupId)["name"]).isEqualTo("Engineering $s")

        // Its members change only through the directory.
        val manual =
            put(
                "/api/v1/shared-teams/${team(groupId)["id"]}/members/${TestTokens.bob.sub}",
                mapOf(
                    "role" to "MEMBER",
                ),
            )
        assertThat(manual.body["code"]).isEqualTo("DIRECTORY_MANAGED")

        // Attached to a product like any shared team; its members get the group's role there.
        put("/api/v1/contexts/$product/shared-teams/${team(groupId)["id"]}")
        put("/api/v1/directory/groups/$groupId/role", mapOf("role" to "MANAGER"))
        val effective = get("/api/v1/contexts/$product/members/effective").list
        assertThat(effective.filter { it["principalId"] == "ext-dora-$s" }.map { it["role"] }).contains("MANAGER")

        // PATCH as providers send it: add a member, rename.
        val patched =
            scim(
                HttpMethod.PATCH,
                "/Groups/$groupId",
                patch(
                    mapOf("op" to "add", "path" to "members", "value" to listOf(mapOf("value" to eli))),
                    mapOf("op" to "replace", "path" to "displayName", "value" to "Engineering team $s"),
                ),
            )
        assertThat(patched.status).withFailMessage(patched.raw).isEqualTo(200)
        assertThat(memberIds(groupId)).containsExactlyInAnyOrder("ext-dora-$s", "ext-eli-$s")
        assertThat(team(groupId)["name"]).isEqualTo("Engineering team $s")

        // Dora signs in for the first time with her real subject: she keeps her membership.
        val real = TestTokens.User(UUID.randomUUID().toString(), "dora-$s", "Dora Tester")
        assertThat(get("/api/v1/me", as_ = real).status).isEqualTo(200)
        assertThat(memberIds(groupId)).containsExactlyInAnyOrder(real.sub, "ext-eli-$s")
        assertThat(scim(HttpMethod.GET, "/Users/$doraId").body["userName"]).isEqualTo("dora-$s")

        // Deactivated (Entra sends "False" as a string): off every team, still in the group.
        val off =
            scim(
                HttpMethod.PATCH,
                "/Users/$eli",
                patch(mapOf("op" to "replace", "path" to "active", "value" to "False")),
            )
        assertThat(off.body["active"]).isEqualTo(false)
        assertThat(memberIds(groupId)).containsExactly(real.sub)

        // Removing a member by path, then deleting the group removes its team.
        scim(
            HttpMethod.PATCH,
            "/Groups/$groupId",
            patch(
                mapOf(
                    "op" to "remove",
                    "path" to "members[value eq \"$doraId\"]",
                ),
            ),
        )
        assertThat(memberIds(groupId)).isEmpty()
        assertThat(scim(HttpMethod.DELETE, "/Groups/$groupId").status).isEqualTo(204)
        assertThat(get("/api/v1/shared-teams").list.none { it["directoryGroup"] == groupId }).isTrue()
        assertThat(scim(HttpMethod.GET, "/Groups/$groupId").status).isEqualTo(404)

        val status = get("/api/v1/directory").body
        assertThat(status["users"] as Int).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `only administrators reach the directory`() {
        assertThat(get("/api/v1/directory", as_ = TestTokens.bob).body["code"]).isEqualTo("ADMIN_REQUIRED")
        val r = call(HttpMethod.GET, "/api/v1/scim/v2/Users", as_ = TestTokens.bob)
        assertThat(r.status).isEqualTo(403)
    }

    private fun Map<String, Any?>.nestedBool(key: String) = (this[key] as Map<*, *>)["supported"] as Boolean
}
