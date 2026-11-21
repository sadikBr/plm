package com.plm.access.infra

import com.plm.access.api.ContextId
import com.plm.access.api.ObjectShare
import com.plm.access.api.ObjectShareStore
import com.plm.access.api.Permission
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class ObjectShareRepository(
    private val jdbc: JdbcClient,
) : ObjectShareStore {
    private val mapper =
        RowMapper { rs, _ ->
            ObjectShare(
                rs.getObject("object_id", UUID::class.java),
                PrincipalId(rs.getString("principal_id")),
                ContextId(rs.getObject("context_id", UUID::class.java)),
                Permission.fromBits(rs.getInt("permissions")),
                rs.getString("note"),
                rs.getTimestamp("expires_at")?.toInstant(),
                rs.getString("granted_by"),
                rs.getTimestamp("granted_at").toInstant(),
            )
        }

    override fun shares(
        tenant: TenantId,
        objectId: UUID,
    ): List<ObjectShare> =
        jdbc
            .sql("select * from object_share where tenant_id = :tenant and object_id = :object order by granted_at")
            .param("tenant", tenant.value)
            .param("object", objectId)
            .query(mapper)
            .list()

    override fun sharedWith(
        tenant: TenantId,
        principal: PrincipalId,
    ): List<ObjectShare> =
        jdbc
            .sql(
                "select * from object_share where tenant_id = :tenant and principal_id = :principal order by granted_at desc",
            ).param("tenant", tenant.value)
            .param("principal", principal.value)
            .query(mapper)
            .list()

    override fun put(
        tenant: TenantId,
        share: ObjectShare,
    ) {
        jdbc
            .sql(
                """
                insert into object_share (tenant_id, object_id, principal_id, context_id, permissions, note, expires_at, granted_by, granted_at)
                values (:tenant, :object, :principal, :context, :bits, :note, :expires, :by, :at)
                on conflict (tenant_id, object_id, principal_id) do update set permissions = excluded.permissions,
                    note = excluded.note, expires_at = excluded.expires_at, granted_by = excluded.granted_by,
                    granted_at = excluded.granted_at, context_id = excluded.context_id
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("object", share.objectId)
            .param("principal", share.principalId.value)
            .param("context", share.contextId.value)
            .param("bits", share.permissions.sumOf { it.bit })
            .param("note", share.note)
            .param("expires", share.expiresAt?.let(Timestamp::from))
            .param("by", share.grantedBy)
            .param("at", Timestamp.from(share.grantedAt))
            .update()
    }

    override fun remove(
        tenant: TenantId,
        objectId: UUID,
        principal: PrincipalId,
    ): Boolean =
        jdbc
            .sql(
                "delete from object_share where tenant_id = :tenant and object_id = :object and principal_id = :principal",
            ).param("tenant", tenant.value)
            .param("object", objectId)
            .param("principal", principal.value)
            .update() > 0

    override fun moved(
        tenant: TenantId,
        objectId: UUID,
        contextId: ContextId,
    ) {
        jdbc
            .sql("update object_share set context_id = :context where tenant_id = :tenant and object_id = :object")
            .param("tenant", tenant.value)
            .param("object", objectId)
            .param("context", contextId.value)
            .update()
    }

    override fun removeAll(
        tenant: TenantId,
        objectId: UUID,
    ) {
        jdbc
            .sql("delete from object_share where tenant_id = :tenant and object_id = :object")
            .param("tenant", tenant.value)
            .param("object", objectId)
            .update()
    }
}
