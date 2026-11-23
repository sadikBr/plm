package com.plm.app

import com.plm.app.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Audit configuration (roadmap 9.9): what is audited, and how long it is kept. */
class AuditConfigApiIT : ApiTest() {
    @Autowired
    lateinit var jdbc: JdbcClient

    private val s = UUID.randomUUID().toString().take(6)
    private val part = "00000000-0000-7000-8000-000000000102"

    @AfterEach
    fun reset() {
        put("/api/v1/audit/config", mapOf("excluded" to emptyList<String>(), "retentionDays" to null))
    }

    @Test
    fun `excluded kinds aren't written, security kinds always are, and old entries are purged`() {
        // Security-relevant kinds can't be left out.
        val refused = put("/api/v1/audit/config", mapOf("excluded" to listOf("access.")))
        assertThat(refused.body["code"]).isEqualTo("INVALID_AUDIT_CONFIG")

        val saved =
            put(
                "/api/v1/audit/config",
                mapOf(
                    "excluded" to listOf("versioning.ObjectCheckedOut"),
                    "retentionDays" to 365,
                ),
            )
        assertThat(saved.status).withFailMessage(saved.raw).isEqualTo(200)
        assertThat(saved.body["alwaysAudited"] as List<*>).contains("access.")

        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Aud $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Aud $s"),
            ).body["id"]
        val id =
            post(
                "/api/v1/objects",
                mapOf(
                    "typeId" to part,
                    "contextId" to product,
                    "name" to "Hinge",
                    "number" to "AUD-$s",
                ),
            ).body["id"]
        post("/api/v1/objects/$id/checkout")
        post("/api/v1/objects/$id/checkin")
        val actions = get("/api/v1/audit?aggregateId=$id").list.map { it["action"] }
        assertThat(
            actions,
        ).contains(
            "versioning.ObjectCreated.v1",
            "versioning.ObjectCheckedIn.v1",
        ).doesNotContain("versioning.ObjectCheckedOut.v1")

        // An entry older than the retention period goes; the purge itself is on record.
        val old = UUID.randomUUID()
        jdbc
            .sql(
                """
                insert into audit_log (event_id, tenant_id, occurred_at, actor, action, aggregate_type, aggregate_id, details)
                values (:e, '00000000-0000-7000-8000-000000000001', :at, 'old-actor', 'versioning.ObjectCreated.v1', 'versioned-object', :a, '{}')
                """,
            ).param("e", UUID.randomUUID())
            .param("at", Timestamp.from(Instant.now().minusSeconds(86_400L * 400)))
            .param("a", old)
            .update()
        val purged = post("/api/v1/audit/purge").body["purged"] as Number
        assertThat(purged.toLong()).isGreaterThanOrEqualTo(1)
        assertThat(get("/api/v1/audit?aggregateId=$old").list).isEmpty()
        assertThat(
            get("/api/v1/audit?limit=20").list.map {
                it["action"]
            },
        ).contains("audit.Purged.v1", "audit.ConfigChanged.v1")

        // Outside a purge, the log stays append-only.
        assertThat(
            runCatching {
                jdbc.sql("delete from audit_log where aggregate_id = :a").param("a", id).update()
            }.isFailure,
        ).isTrue()
        assertThat(get("/api/v1/audit/kinds").list.map { it["kind"] }).contains("versioning.", "access.")
    }
}
