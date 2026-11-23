package com.plm.access.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Permission
import com.plm.access.api.SqlPredicate
import com.plm.access.infra.AccessRepository
import com.plm.access.infra.SecurityLabelRepository
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
    private val labels: SecurityLabelRepository,
    private val clock: Clock,
) : AccessService {
    /** The labels on the target object [actor] doesn't pass (roadmap 9.7); none for site administrators. */
    private fun blocking(
        actor: Actor,
        target: AccessTarget,
    ) = if (actor.siteAdmin || target.objectId == null) {
        emptyList()
    } else {
        labels.blocking(
            actor.tenant.value,
            actor.principal.value,
            target.objectId,
            target.contextId.value,
            clock.instant(),
        )
    }

    /** Policies and shares alone, before labels. */
    private fun withoutLabels(
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
        val bits = policies or shared
        // A label the person isn't cleared for hides the object entirely.
        if (bits != 0 && blocking(actor, target).isNotEmpty()) return emptySet()
        return Permission.fromBits(bits)
    }

    override fun require(
        actor: Actor,
        target: AccessTarget,
        permission: Permission,
    ) {
        if (!has(actor, target, permission)) {
            // Only someone the context lets in hears about labels: others don't learn the object is there.
            val blocked = if (permission in withoutLabels(actor, target)) blocking(actor, target) else emptyList()
            if (blocked.isNotEmpty()) {
                throw ForbiddenException(
                    "LABEL_CLEARANCE_REQUIRED",
                    "This object is marked " + blocked.joinToString { "${it.labelName}: ${it.valueKey}" } +
                        ": you need that clearance, or an agreement that covers it",
                    mapOf("labels" to blocked.map { mapOf("label" to it.labelName, "value" to it.valueKey) }),
                )
            }
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
            "(($policies or ${access.sharePredicate(
                objectColumn,
            )}) and ${labels.predicate(objectColumn, columns.contextId)})",
            params + ("acl_now" to Timestamp.from(clock.instant())),
        )
    }
}
