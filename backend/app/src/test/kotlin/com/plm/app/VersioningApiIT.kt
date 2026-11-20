package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens.alice
import com.plm.app.support.TestTokens.bob
import com.plm.app.support.TestTokens.carol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.util.UUID

/** Drives the core flow end to end over HTTP, the way the UI and integrations will. */
class VersioningApiIT : ApiTest() {
    private val partType = "00000000-0000-7000-8000-000000000102"

    /** alice (site admin) sets up Acme / Drone and puts bob on the Drone team as a MEMBER. */
    private fun setUpProduct(): String {
        // Make sure bob is in the directory, as he would be after his first sign-in.
        get("/api/v1/me", as_ = bob)
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
        val productId = product.body["id"] as String
        val member = put("/api/v1/contexts/$productId/members/${bob.sub}", mapOf("role" to "MEMBER"))
        assertThat(member.status).isEqualTo(200)
        return productId
    }

    @Test
    fun `full versioning flow over HTTP, as a team member`() {
        val product = setUpProduct()
        assertThat(call(HttpMethod.GET, "/api/v1/contexts/$product/permissions", as_ = bob).raw)
            .isEqualTo("[\"CREATE\",\"MODIFY\",\"READ\",\"REVISE\"]")

        val created =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to partType,
                    "contextId" to product,
                    "name" to "Motor mount",
                    "attributes" to mapOf("material" to "AL6061", "mass" to mapOf("value" to 0.12, "unit" to "kg")),
                ),
                as_ = bob,
            )
        assertThat(created.status).isEqualTo(201)
        assertThat(created.body["version"]).isEqualTo("A.1")
        assertThat(created.body["createdBy"]).isEqualTo(bob.sub)
        val id = created.body["id"]

        val workingCopy = post("/api/v1/objects/$id/checkout", as_ = bob)
        assertThat(workingCopy.body["version"]).isEqualTo("A.2")

        val aliceTries = post("/api/v1/objects/$id/checkout")
        assertThat(aliceTries.status).isEqualTo(409)
        assertThat(aliceTries.body["code"]).isEqualTo("ALREADY_CHECKED_OUT")
        assertThat(aliceTries.body["detail"] as String).contains("checked out by Bob Builder")
        assertThat(aliceTries.nested("details")).containsEntry("checkedOutBy", bob.sub)

        val patched =
            call(
                HttpMethod.PATCH,
                "/api/v1/objects/$id/working-copy",
                as_ = bob,
                body =
                    mapOf(
                        "attributes" to mapOf("material" to "Ti"),
                    ),
            )
        assertThat(patched.status).isEqualTo(200)
        val checkedIn = post("/api/v1/objects/$id/checkin", mapOf("comment" to "Titanium"), as_ = bob)
        assertThat(checkedIn.body["version"]).isEqualTo("A.2")
        // Revise is only allowed once released; members can't promote.
        val tooEarly = post("/api/v1/objects/$id/revise", as_ = bob)
        assertThat(tooEarly.status).isEqualTo(409)
        assertThat(tooEarly.body["code"]).isEqualTo("REVISE_NOT_ALLOWED")
        assertThat(post("/api/v1/objects/$id/state", mapOf("state" to "UNDER_REVIEW"), as_ = bob).status).isEqualTo(403)
        val lifecycle = get("/api/v1/objects/$id/lifecycle").body
        assertThat(
            lifecycle,
        ).containsEntry("state", "IN_WORK").containsEntry("editable", true).containsEntry("revisable", false)
        assertThat(lifecycle["nextStates"]).isEqualTo(listOf("UNDER_REVIEW", "CANCELED"))

        assertThat(post("/api/v1/objects/$id/state", mapOf("state" to "UNDER_REVIEW")).status).isEqualTo(200)
        val released = post("/api/v1/objects/$id/state", mapOf("state" to "RELEASED", "comment" to "Approved by DR-12"))
        assertThat(released.nested("latest")["state"]).isEqualTo("RELEASED")
        assertThat(post("/api/v1/objects/$id/checkout", as_ = bob).body["code"]).isEqualTo("NOT_EDITABLE")

        val revised = post("/api/v1/objects/$id/revise", as_ = bob)
        assertThat(revised.body["version"]).isEqualTo("B.1")
        assertThat(revised.nested("latest")["state"]).isEqualTo("IN_WORK")

        val history = get("/api/v1/objects/$id/iterations", as_ = bob).list
        assertThat(history.map { it["version"] }).containsExactly("B.1", "A.2", "A.1")

        val listed = get("/api/v1/objects?contextId=$product", as_ = bob).list
        assertThat(listed.map { it["id"] }).containsExactly(id)

        val audit = get("/api/v1/audit?aggregateId=$id").list
        assertThat(audit.map { it["action"] }).containsExactly(
            "versioning.ObjectRevised.v1",
            "versioning.ObjectStateChanged.v1",
            "versioning.ObjectStateChanged.v1",
            "versioning.ObjectCheckedIn.v1",
            "versioning.WorkingCopyUpdated.v1",
            "versioning.ObjectCheckedOut.v1",
            "versioning.ObjectCreated.v1",
        )
        assertThat(audit.filter { it["action"] == "versioning.ObjectStateChanged.v1" }).allMatch {
            it["actor"] ==
                alice.sub
        }
        assertThat(audit.filterNot { it["action"] == "versioning.ObjectStateChanged.v1" }).allMatch {
            it["actor"] ==
                bob.sub
        }
    }

    @Test
    fun `people outside the team cannot see the product or its objects`() {
        val product = setUpProduct()
        val id =
            post(
                "/api/v1/objects",
                mapOf("typeId" to partType, "contextId" to product, "name" to "Secret"),
                as_ = bob,
            ).body["id"]

        assertThat(get("/api/v1/contexts/$product", as_ = carol).status).isEqualTo(404)
        assertThat(get("/api/v1/objects/$id", as_ = carol).body["code"]).isEqualTo("OBJECT_NOT_FOUND")
        assertThat(get("/api/v1/objects?contextId=$product", as_ = carol).body["code"]).isEqualTo("CONTEXT_NOT_FOUND")
        assertThat(post("/api/v1/objects/$id/checkout", as_ = carol).status).isEqualTo(404)

        // Adding carol as a VIEWER lets her read, but not change.
        put("/api/v1/contexts/$product/members/${carol.sub}", mapOf("role" to "VIEWER"))
        assertThat(get("/api/v1/objects/$id", as_ = carol).status).isEqualTo(200)
        val denied = post("/api/v1/objects/$id/checkout", as_ = carol)
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied.body["code"]).isEqualTo("ACCESS_DENIED")
    }

    @Test
    fun `team members cannot manage the team`() {
        val product = setUpProduct()

        val attempt = put("/api/v1/contexts/$product/members/${carol.sub}", mapOf("role" to "MANAGER"), as_ = bob)
        assertThat(attempt.status).isEqualTo(403)
        assertThat(get("/api/v1/contexts/$product/members").list.map { it["principalId"] })
            .containsExactlyInAnyOrder(alice.sub, bob.sub)
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

        assertThat(post("/api/v1/objects", "not an object").status).isEqualTo(400)
    }
}
