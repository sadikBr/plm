package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Personal workspaces (roadmap 8.1): file states, uploads, all-or-nothing check-in, new files. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceApiIT : ApiTest() {
    private val document = "00000000-0000-7000-8000-000000000101"
    private val s = UUID.randomUUID().toString().take(6)
    private lateinit var product: String

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** An upload ticket, filled with [bytes]: its id. */
    private fun upload(
        name: String,
        bytes: ByteArray,
        as_: TestTokens.User = TestTokens.alice,
    ): String {
        val ticket = post("/api/v1/vault/uploads", mapOf("fileName" to name, "size" to bytes.size), as_)
        assertThat(ticket.status).withFailMessage(ticket.raw).isEqualTo(201)
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

    /** A document with [bytes] as its primary file, checked in. */
    private fun doc(
        name: String,
        bytes: ByteArray,
    ): String {
        val r =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to document,
                    "contextId" to product,
                    "name" to name,
                    "number" to "$name-$s",
                ),
            )
        assertThat(r.status).withFailMessage(r.raw).isEqualTo(201)
        val id = r.body["id"] as String
        post("/api/v1/objects/$id/checkout")
        val a =
            post("/api/v1/objects/$id/content", mapOf("uploadId" to upload("$name.txt", bytes), "role" to "PRIMARY"))
        assertThat(a.status).withFailMessage(a.raw).isIn(200, 201)
        post("/api/v1/objects/$id/checkin", mapOf("comment" to "first"))
        return id
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(ws: String) = get("/api/v1/workspaces/$ws").body["items"] as List<Map<String, Any?>>

    private fun state(
        ws: String,
        id: String,
    ) = items(ws).first { it["objectId"] == id }["fileState"]

    @BeforeAll
    fun data() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "WS $s"),
            ).body["id"]
        product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "WS $s"),
            ).body["id"] as String
        get("/api/v1/me", as_ = TestTokens.bob)
        put("/api/v1/contexts/$product/members/${TestTokens.bob.sub}", mapOf("role" to "MEMBER"))
    }

    @Test
    fun `file states follow the person's computer and the system`() {
        val a = doc("WA", "one".toByteArray())
        val ws = post("/api/v1/workspaces", mapOf("name" to "Bracket $s", "contextId" to product))
        assertThat(ws.status).withFailMessage(ws.raw).isEqualTo(201)
        val w = ws.body["id"] as String
        assertThat(post("/api/v1/workspaces", mapOf("name" to "Bracket $s", "contextId" to product)).body["code"])
            .isEqualTo("DUPLICATE_WORKSPACE")
        post("/api/v1/workspaces/$w/items", mapOf("ids" to listOf(a)))
        assertThat(state(w, a)).isEqualTo("IN_SYNC")

        // Their computer changed the file: modified.
        post(
            "/api/v1/workspaces/$w/local",
            mapOf(
                "files" to listOf(mapOf("objectId" to a, "sha256" to sha("two".toByteArray()))),
            ),
        )
        assertThat(state(w, a)).isEqualTo("MODIFIED")

        // Someone else checks in a newer iteration meanwhile: a conflict, and an upload is refused.
        post("/api/v1/objects/$a/checkout", as_ = TestTokens.bob)
        assertThat(state(w, a)).isEqualTo("CHECKED_OUT_BY_OTHER")
        post("/api/v1/objects/$a/checkin", mapOf("comment" to "bob"), TestTokens.bob)
        assertThat(state(w, a)).isEqualTo("CONFLICT")
        val refused =
            post(
                "/api/v1/workspaces/$w/upload",
                mapOf(
                    "objectId" to a,
                    "uploadId" to upload("WA.txt", "two".toByteArray()),
                ),
            )
        assertThat(refused.body["code"]).isEqualTo("OUT_OF_DATE")

        // Bringing it up to date (the client reports the new base) leaves their change to redo: modified.
        val downloads = get("/api/v1/workspaces/$w/downloads?ids=$a").list
        assertThat(downloads).hasSize(1)
        post(
            "/api/v1/workspaces/$w/local",
            mapOf(
                "files" to
                    listOf(
                        mapOf(
                            "objectId" to a,
                            "iterationId" to downloads[0]["iterationId"],
                            "sha256" to sha("two".toByteArray()),
                        ),
                    ),
            ),
        )
        assertThat(state(w, a)).isEqualTo("MODIFIED")

        // Uploading checks it out and puts the file in the working copy; check-in makes it in sync.
        val up =
            post(
                "/api/v1/workspaces/$w/upload",
                mapOf(
                    "objectId" to a,
                    "uploadId" to upload("WA.txt", "two".toByteArray()),
                ),
            )
        assertThat(up.status).withFailMessage(up.raw).isEqualTo(200)
        assertThat(up.body["fileState"]).isEqualTo("CHECKED_OUT")
        val done = post("/api/v1/workspaces/$w/check-in", mapOf("comment" to "from my desk"))
        assertThat(done.list.map { it["version"] }).containsExactly("A.4")
        assertThat(state(w, a)).isEqualTo("IN_SYNC")
        assertThat(
            get("/api/v1/workspaces/$w/history").list.map { it["kind"] },
        ).contains("CHECKED_IN", "UPLOADED", "ADDED")

        // Nobody else sees the workspace.
        assertThat(get("/api/v1/workspaces/$w", TestTokens.bob).body["code"]).isEqualTo("WORKSPACE_NOT_FOUND")
    }

    @Test
    fun `checking in several is all or nothing, and new files become documents`() {
        val a = doc("WB", "b".toByteArray())
        val b = doc("WC", "c".toByteArray())
        val w = post("/api/v1/workspaces", mapOf("name" to "Batch $s", "contextId" to product)).body["id"] as String
        post("/api/v1/workspaces/$w/items", mapOf("ids" to listOf(a, b)))
        val out = post("/api/v1/workspaces/$w/check-out", mapOf("ids" to listOf(a, b)))
        assertThat(out.list.map { it["changed"] }).containsExactly(true, true)

        // Bob takes one over (an administrator could): here, simply undo hers and let him check it out.
        post("/api/v1/workspaces/$w/undo", mapOf("ids" to listOf(b)))
        post("/api/v1/objects/$b/checkout", as_ = TestTokens.bob)
        val refused = post("/api/v1/workspaces/$w/check-in", mapOf("ids" to listOf(a, b)))
        assertThat(refused.body["code"]).isEqualTo("NOT_CHECKED_OUT")
        assertThat(refused.body["detail"] as String).contains("WC-$s")
        // Nothing was checked in: A is still checked out to her.
        assertThat(state(w, a)).isEqualTo("CHECKED_OUT")
        assertThat(post("/api/v1/workspaces/$w/check-in", mapOf("ids" to listOf(a))).status).isEqualTo(200)

        // A new file from her computer becomes a document in the workspace's product.
        val nf =
            post(
                "/api/v1/workspaces/$w/new-files",
                mapOf(
                    "uploadId" to upload("Notes.txt", "n".toByteArray()),
                    "fileName" to "Notes.txt",
                ),
            )
        assertThat(nf.status).withFailMessage(nf.raw).isEqualTo(201)
        val created =
            post(
                "/api/v1/workspaces/$w/new-files/${nf.body["id"]}/create",
                mapOf(
                    "typeId" to document,
                    "name" to "Notes",
                ),
            )
        assertThat(created.status).withFailMessage(created.raw).isEqualTo(201)
        assertThat(created.body["fileState"]).isEqualTo("IN_SYNC")
        assertThat((created.body["file"] as Map<*, *>)["fileName"]).isEqualTo("Notes.txt")
        assertThat(get("/api/v1/workspaces/$w").body["newFiles"] as List<*>).isEmpty()
        val list = get("/api/v1/workspaces").list
        assertThat((list.first { it["id"] == w }["counts"] as Map<*, *>).keys).contains("IN_SYNC")
    }

    @Test
    fun `a personal token acts as its owner, within its scopes, until revoked`() {
        val issued = post("/api/v1/me/tokens", mapOf("name" to "Laptop sync", "scopes" to listOf("read")))
        assertThat(issued.status).withFailMessage(issued.raw).isEqualTo(201)
        val secret = issued.body["secret"] as String
        val id = issued.nested("token")["id"]
        assertThat(secret).startsWith("plm_")
        val me = call(org.springframework.http.HttpMethod.GET, "/api/v1/workspaces", as_ = null, token = secret)
        assertThat(me.status).isEqualTo(200)
        // Read only: no writing.
        val write =
            call(
                org.springframework.http.HttpMethod.POST,
                "/api/v1/workspaces",
                as_ = null,
                body = mapOf("name" to "Nope $s", "contextId" to product),
                token = secret,
            )
        assertThat(write.status).isEqualTo(403)
        // Only for people, scopes they may have, listed for them alone.
        val adminScope = post("/api/v1/me/tokens", mapOf("name" to "x", "scopes" to listOf("admin")), TestTokens.bob)
        assertThat(adminScope.body["code"]).isEqualTo("INVALID_SCOPES")
        assertThat(get("/api/v1/me/tokens").list.map { it["name"] }).contains("Laptop sync")
        assertThat(get("/api/v1/me/tokens", TestTokens.bob).list.map { it["name"] }).doesNotContain("Laptop sync")
        assertThat(
            call(org.springframework.http.HttpMethod.DELETE, "/api/v1/me/tokens/$id", TestTokens.bob).status,
        ).isEqualTo(404)
        assertThat(call(org.springframework.http.HttpMethod.DELETE, "/api/v1/me/tokens/$id").status).isEqualTo(204)
        assertThat(
            call(org.springframework.http.HttpMethod.GET, "/api/v1/workspaces", as_ = null, token = secret).status,
        ).isEqualTo(401)
    }
}
