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

@Service
@Transactional(readOnly = true)
internal class DefaultAccessService(
    private val access: AccessRepository,
) : AccessService {
    override fun permissions(
        actor: Actor,
        target: AccessTarget,
    ): Set<Permission> {
        if (actor.siteAdmin) return Permission.entries.toSet()
        return Permission.fromBits(
            access.permissionBits(actor.tenant, actor.principal, target.contextId, target.typeId, target.state),
        )
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
        return SqlPredicate(
            sql = access.permissionPredicate(columns.contextId, columns.typeId, columns.state),
            params =
                mapOf(
                    "acl_tenant" to actor.tenant.value,
                    "acl_principal" to actor.principal.value,
                    "acl_bit" to permission.bit,
                ),
        )
    }
}
