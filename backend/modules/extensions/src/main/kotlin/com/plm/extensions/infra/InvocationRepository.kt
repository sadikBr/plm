package com.plm.extensions.infra

import com.plm.extensions.api.ActionInvocation
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class InvocationRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        tenant: TenantId,
        i: ActionInvocation,
    ) {
        jdbc
            .sql(
                """
                insert into action_invocation (id, tenant_id, action_id, extension_id, object_id, object_number, actor, at,
                                               status, http_status, duration_ms, message)
                values (:id, :tenant, :action, :extension, :object, :number, :actor, :at, :status, :http, :duration, :message)
                """.trimIndent(),
            ).param("id", i.id)
            .param("tenant", tenant.value)
            .param("action", i.actionId)
            .param("extension", i.extensionId)
            .param("object", i.objectId)
            .param("number", i.objectNumber)
            .param("actor", i.actor)
            .param("at", Timestamp.from(i.at))
            .param("status", i.status)
            .param("http", i.httpStatus)
            .param("duration", i.durationMs)
            .param("message", i.message?.take(500))
            .update()
    }

    fun latest(
        tenant: TenantId,
        extensionId: String,
        limit: Int,
    ): List<ActionInvocation> =
        jdbc
            .sql(
                "select * from action_invocation where tenant_id = :tenant and extension_id = :ext order by seq desc limit :limit",
            ).param("tenant", tenant.value)
            .param("ext", extensionId)
            .param("limit", limit)
            .query { rs, _ ->
                ActionInvocation(
                    id = rs.getObject("id", UUID::class.java),
                    actionId = rs.getString("action_id"),
                    extensionId = rs.getString("extension_id"),
                    objectId = rs.getObject("object_id", UUID::class.java),
                    objectNumber = rs.getString("object_number"),
                    actor = rs.getString("actor"),
                    at = rs.getTimestamp("at").toInstant(),
                    status = rs.getString("status"),
                    httpStatus = rs.getObject("http_status") as Int?,
                    durationMs = rs.getLong("duration_ms"),
                    message = rs.getString("message"),
                )
            }.list()
}
