package com.plm.integration.domain

import com.plm.integration.api.DistributionMessage
import com.plm.integration.api.DistributionRule
import com.plm.integration.api.PlantAttribute
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class TargetRow(
    val id: UUID,
    val tenant: UUID,
    val name: String,
    val system: String,
    val plant: String,
    val endpoint: String?,
    val secret: String,
    val attributes: List<PlantAttribute>,
    val active: Boolean,
    val createdAt: Instant,
)

internal data class ObjectRow(
    val tenant: UUID,
    val objectId: UUID,
    val targetId: UUID,
    val plantValues: Map<String, String>,
    val status: String,
    val version: String?,
    val erpKey: String?,
    val message: String?,
    val updatedAt: Instant,
)

/** A message due to be POSTed. */
internal data class DueMessage(
    val id: UUID,
    val objectId: UUID,
    val targetId: UUID,
    val endpoint: String,
    val secret: String,
    val body: String,
    val attempts: Int,
)

@Repository
internal class DistributionRepository(
    private val jdbc: JdbcClient,
) {
    val json: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun ResultSet.target() =
        TargetRow(
            getObject("id", UUID::class.java),
            getObject("tenant_id", UUID::class.java),
            getString("name"),
            getString("system"),
            getString("plant"),
            getString("endpoint"),
            getString("secret"),
            json.readValue(getString("attributes")),
            getBoolean("active"),
            getTimestamp("created_at").toInstant(),
        )

    fun targets(tenant: UUID): List<TargetRow> =
        jdbc
            .sql("select * from distribution_target where tenant_id = :t order by name")
            .param("t", tenant)
            .query { rs, _ -> rs.target() }
            .list()

    fun target(id: UUID): TargetRow? =
        jdbc
            .sql("select * from distribution_target where id = :id")
            .param("id", id)
            .query { rs, _ -> rs.target() }
            .optional()
            .orElse(null)

    fun insertTarget(t: TargetRow) {
        jdbc
            .sql(
                """
                insert into distribution_target (id, tenant_id, name, system, plant, endpoint, secret, attributes, active, created_at)
                values (:id, :t, :name, :system, :plant, :endpoint, :secret, cast(:attributes as jsonb), :active, :at)
                """,
            ).param("id", t.id)
            .param("t", t.tenant)
            .param("name", t.name)
            .param("system", t.system)
            .param("plant", t.plant)
            .param("endpoint", t.endpoint)
            .param("secret", t.secret)
            .param("attributes", json.writeValueAsString(t.attributes))
            .param("active", t.active)
            .param("at", Timestamp.from(t.createdAt))
            .update()
    }

    fun updateTarget(t: TargetRow) {
        jdbc
            .sql(
                """
                update distribution_target set name = :name, system = :system, plant = :plant, endpoint = :endpoint,
                    attributes = cast(:attributes as jsonb), active = :active where id = :id
                """,
            ).param("id", t.id)
            .param("name", t.name)
            .param("system", t.system)
            .param("plant", t.plant)
            .param("endpoint", t.endpoint)
            .param("attributes", json.writeValueAsString(t.attributes))
            .param("active", t.active)
            .update()
    }

    fun deleteTarget(id: UUID) {
        jdbc.sql("delete from distribution_target where id = :id").param("id", id).update()
    }

    fun rules(target: UUID): List<DistributionRule> =
        jdbc
            .sql("select * from distribution_rule where target_id = :t order by state, type_id")
            .param("t", target)
            .query { rs, _ ->
                DistributionRule(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("type_id", UUID::class.java),
                    rs.getObject("context_id", UUID::class.java),
                    rs.getString("state"),
                )
            }.list()

    fun insertRule(
        target: UUID,
        r: DistributionRule,
    ) {
        jdbc
            .sql(
                "insert into distribution_rule (id, target_id, type_id, context_id, state) values (:id, :t, :type, :context, :state)",
            ).param("id", r.id)
            .param("t", target)
            .param("type", r.typeId)
            .param("context", r.contextId)
            .param("state", r.state)
            .update()
    }

    fun deleteRule(id: UUID) {
        jdbc.sql("delete from distribution_rule where id = :id").param("id", id).update()
    }

    fun counts(target: UUID): Pair<Int, Int> =
        jdbc
            .sql(
                "select count(*) filter (where status in ('PENDING', 'SENT')), " +
                    "count(*) filter (where status in ('FAILED', 'REJECTED')) from distribution_message where target_id = :t",
            ).param("t", target)
            .query { rs, _ -> rs.getInt(1) to rs.getInt(2) }
            .single()

    private fun ResultSet.objectRow() =
        ObjectRow(
            getObject("tenant_id", UUID::class.java),
            getObject("object_id", UUID::class.java),
            getObject("target_id", UUID::class.java),
            json.readValue(getString("plant_values")),
            getString("status"),
            getString("version"),
            getString("erp_key"),
            getString("message"),
            getTimestamp("updated_at").toInstant(),
        )

    fun objectRows(objectId: UUID): List<ObjectRow> =
        jdbc
            .sql("select * from object_distribution where object_id = :o")
            .param("o", objectId)
            .query { rs, _ -> rs.objectRow() }
            .list()

    fun objectRow(
        objectId: UUID,
        targetId: UUID,
    ): ObjectRow? =
        jdbc
            .sql("select * from object_distribution where object_id = :o and target_id = :t")
            .param("o", objectId)
            .param("t", targetId)
            .query { rs, _ -> rs.objectRow() }
            .optional()
            .orElse(null)

    fun saveObjectRow(r: ObjectRow) {
        jdbc
            .sql(
                """
                insert into object_distribution (tenant_id, object_id, target_id, plant_values, status, version, erp_key, message, updated_at)
                values (:t, :o, :target, cast(:values as jsonb), :status, :version, :erp, :message, :at)
                on conflict (object_id, target_id) do update set plant_values = excluded.plant_values, status = excluded.status,
                    version = excluded.version, erp_key = excluded.erp_key, message = excluded.message, updated_at = excluded.updated_at
                """,
            ).param("t", r.tenant)
            .param("o", r.objectId)
            .param("target", r.targetId)
            .param("values", json.writeValueAsString(r.plantValues))
            .param("status", r.status)
            .param("version", r.version)
            .param("erp", r.erpKey)
            .param("message", r.message)
            .param("at", Timestamp.from(r.updatedAt))
            .update()
    }

    fun insertMessage(
        id: UUID,
        tenant: UUID,
        target: UUID,
        objectId: UUID,
        version: String,
        payload: Map<String, Any?>,
        at: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into distribution_message (id, tenant_id, target_id, object_id, version, payload, status, next_attempt, created_at)
                values (:id, :t, :target, :o, :version, cast(:payload as jsonb), 'PENDING', :at, :at)
                """,
            ).param("id", id)
            .param("t", tenant)
            .param("target", target)
            .param("o", objectId)
            .param("version", version)
            .param("payload", json.writeValueAsString(payload))
            .param("at", Timestamp.from(at))
            .update()
    }

    private fun ResultSet.message(): DistributionMessage {
        val payload: Map<String, Any?> = json.readValue(getString("payload"))
        return DistributionMessage(
            getObject("id", UUID::class.java),
            getObject("target_id", UUID::class.java),
            getObject("object_id", UUID::class.java),
            ((payload["object"] as? Map<*, *>)?.get("number") ?: "").toString(),
            getString("version"),
            getString("status"),
            getInt("attempts"),
            getString("last_error"),
            getString("answer"),
            getTimestamp("created_at").toInstant(),
            getTimestamp("sent_at")?.toInstant(),
            getTimestamp("answered_at")?.toInstant(),
            payload,
        )
    }

    fun messages(
        tenant: UUID,
        objectId: UUID?,
        targetId: UUID?,
        limit: Int,
    ): List<DistributionMessage> =
        jdbc
            .sql(
                """
                select * from distribution_message where tenant_id = :t
                  and (cast(:o as uuid) is null or object_id = :o) and (cast(:target as uuid) is null or target_id = :target)
                order by created_at desc limit :limit
                """,
            ).param("t", tenant)
            .param("o", objectId)
            .param("target", targetId)
            .param("limit", limit)
            .query { rs, _ -> rs.message() }
            .list()

    fun message(id: UUID): DistributionMessage? =
        jdbc
            .sql("select * from distribution_message where id = :id")
            .param("id", id)
            .query { rs, _ -> rs.message() }
            .optional()
            .orElse(null)

    fun sent(
        id: UUID,
        at: Instant,
    ) {
        jdbc
            .sql(
                "update distribution_message set status = 'SENT', sent_at = :at, attempts = attempts + 1, last_error = null where id = :id",
            ).param("id", id)
            .param("at", Timestamp.from(at))
            .update()
    }

    fun failedAttempt(
        id: UUID,
        error: String,
        retryAt: Instant?,
    ) {
        jdbc
            .sql(
                """
                update distribution_message set attempts = attempts + 1, last_error = :e,
                    status = case when cast(:retry as timestamptz) is null then 'FAILED' else 'PENDING' end,
                    next_attempt = coalesce(cast(:retry as timestamptz), next_attempt)
                where id = :id
                """,
            ).param("id", id)
            .param("e", error.take(500))
            .param("retry", retryAt?.let(Timestamp::from))
            .update()
    }

    fun answer(
        id: UUID,
        status: String,
        answer: String?,
        at: Instant,
    ) {
        jdbc
            .sql("update distribution_message set status = :s, answer = :a, answered_at = :at where id = :id")
            .param("id", id)
            .param("s", status)
            .param("a", answer?.take(1000))
            .param("at", Timestamp.from(at))
            .update()
    }

    /** Due messages of targets with an endpoint, claimed by pushing their next attempt past [lease]. */
    fun claim(
        now: Instant,
        lease: Instant,
        limit: Int,
    ): List<DueMessage> {
        val due =
            jdbc
                .sql(
                    """
                    select m.id, m.object_id, m.target_id, t.endpoint, t.secret, m.payload::text as body, m.attempts
                    from distribution_message m join distribution_target t on t.id = m.target_id
                    where m.status = 'PENDING' and m.next_attempt <= :now and t.endpoint is not null and t.active
                    order by m.next_attempt limit :limit for update of m skip locked
                    """,
                ).param("now", Timestamp.from(now))
                .param("limit", limit)
                .query { rs, _ ->
                    DueMessage(
                        rs.getObject("id", UUID::class.java),
                        rs.getObject("object_id", UUID::class.java),
                        rs.getObject("target_id", UUID::class.java),
                        rs.getString("endpoint"),
                        rs.getString("secret"),
                        rs.getString("body"),
                        rs.getInt("attempts"),
                    )
                }.list()
        due.forEach {
            jdbc
                .sql(
                    "update distribution_message set next_attempt = :lease where id = :id",
                ).param("id", it.id)
                .param("lease", Timestamp.from(lease))
                .update()
        }
        return due
    }
}
