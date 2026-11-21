package com.plm.access.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Permission
import com.plm.access.api.SqlPredicate
import com.plm.access.infra.AccessRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock

@Service
@Transactional(readOnly = true)
internal class DefaultAccessService(
    private val access: AccessRepository,
    private val clock: Clock,
) : AccessService {
    override fun permissions(
        actor: Actor,
        target: AccessTarget,
    ): Set<Permission> {
        if (actor.siteAdmin) return Permission.entries.toSet()
        val policies =
            access.permissionBits(
                actor.tenant,
                actor.principal,
                target.contextId,
                target.typeId,
                target.state,
            )
        val shared =
            target.objectId?.let {
                access.shareBits(
                    actor.tenant,
                    actor.principal,
                    it,
                    Timestamp.from(clock.instant()),
                )
            }
                ?: 0
        return Permission.fromBits(policies or shared)
    }

    override fun require(
        actor: Actor,
        target: AccessTarget,
        permission: Permission,
    ) {
        if (!has(actor, target, permission)) {
            throw ForbiddenException(
                "ACCESS_DENIED",
                "You do not have $permission permission here",
                mapOf("permission" to permission, "contextId" to target.contextId.value),
            )
        }
    }

    override fun filter(
        actor: Actor,
        permission: Permission,
        columns: AccessColumns,
    ): SqlPredicate {
        if (actor.siteAdmin) return SqlPredicate("true", emptyMap())
        val policies = access.permissionPredicate(columns.contextId, columns.typeId, columns.state)
        val params =
            mapOf(
                "acl_tenant" to actor.tenant.value,
                "acl_principal" to actor.principal.value,
                "acl_bit" to permission.bit,
            )
        val objectColumn = columns.objectId ?: return SqlPredicate(policies, params)
        return SqlPredicate(
            "($policies or ${access.sharePredicate(objectColumn)})",
            params + ("acl_now" to Timestamp.from(clock.instant())),
        )
    }
}
