package com.plm.versioning.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ObjectShare
import com.plm.access.api.ObjectShareStore
import com.plm.access.api.Permission
import com.plm.identity.api.PrincipalDirectory
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.ObjectShared
import com.plm.versioning.api.ObjectUnshared
import com.plm.versioning.api.Share
import com.plm.versioning.api.ShareRequest
import com.plm.versioning.api.SharedWithMe
import com.plm.versioning.api.SharingService
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultSharingService(
    private val store: ObjectShareStore,
    private val versioning: VersioningService,
    private val access: AccessService,
    private val directory: PrincipalDirectory,
    private val events: DomainEvents,
    private val clock: Clock,
) : SharingService {
    private fun ObjectShare.dto() =
        Share(
            objectId,
            principalId.value,
            permissions.sortedBy { it.bit }.map { it.name },
            note,
            expiresAt,
            grantedBy,
            grantedAt,
        )

    @Transactional(readOnly = true)
    override fun shares(
        actor: Actor,
        objectId: MasterId,
    ): List<Share> {
        val obj = versioning.get(actor, objectId)
        return store.shares(actor.tenant, obj.id.value).map { it.dto() }
    }

    private fun requireSharer(
        actor: Actor,
        obj: VersionedObject,
    ) {
        if (actor.siteAdmin || obj.owner == actor.principal.value) return
        if (Permission.MANAGE in access.permissions(actor, AccessTarget(obj.contextId))) return
        throw ForbiddenException(
            "ACCESS_DENIED",
            "Only its owner, its context's managers or an administrator can share it",
        )
    }

    override fun share(
        actor: Actor,
        objectId: MasterId,
        request: ShareRequest,
    ): Share {
        val obj = versioning.get(actor, objectId)
        requireSharer(actor, obj)
        val principal =
            // By username first: a failed lookup by id would mark the transaction for rollback.
            directory.findByUsername(actor, request.principalId)
                ?: directory.get(actor, PrincipalId(request.principalId))
        if (principal.id ==
            actor.principal
        ) {
            throw ValidationException("INVALID_SHARE", "You can't share an object with yourself")
        }
        val now = clock.instant()
        if (request.expiresAt != null && !request.expiresAt.isAfter(now)) {
            throw ValidationException("INVALID_SHARE", "The end date must be in the future")
        }
        val permissions = if (request.modify) setOf(Permission.READ, Permission.MODIFY) else setOf(Permission.READ)
        val share =
            ObjectShare(
                obj.id.value,
                principal.id,
                obj.contextId,
                permissions,
                request.note
                    ?.trim()
                    ?.take(500)
                    ?.ifEmpty { null },
                request.expiresAt,
                actor.principal.value,
                now,
            )
        store.put(actor.tenant, share)
        events.publish(
            actor,
            ObjectShared(
                obj.id.value,
                obj.number,
                obj.typeId.value,
                obj.contextId.value,
                obj.latest.id.value,
                obj.latest.version,
                principal.id.value,
                share.permissions.map { it.name },
                request.expiresAt?.toString(),
            ),
        )
        return share.dto()
    }

    override fun unshare(
        actor: Actor,
        objectId: MasterId,
        principalId: String,
    ) {
        val obj = versioning.get(actor, objectId)
        // People may also give back what was shared with them.
        if (principalId != actor.principal.value) requireSharer(actor, obj)
        if (store.remove(actor.tenant, obj.id.value, PrincipalId(principalId))) {
            events.publish(
                actor,
                ObjectUnshared(
                    obj.id.value,
                    obj.number,
                    obj.typeId.value,
                    obj.contextId.value,
                    obj.latest.id.value,
                    obj.latest.version,
                    principalId,
                ),
            )
        }
    }

    @Transactional(readOnly = true)
    override fun sharedWithMe(actor: Actor): List<SharedWithMe> {
        val now = clock.instant()
        val shares = store.sharedWith(actor.tenant, actor.principal).filter { s -> s.expiresAt?.isAfter(now) ?: true }
        if (shares.isEmpty()) return emptyList()
        val objects =
            versioning
                .list(
                    actor,
                    ObjectQuery(
                        ids =
                            shares.map {
                                MasterId(it.objectId)
                            },
                        limit = 200,
                    ),
                ).associateBy { it.id.value }
        return shares.mapNotNull { s ->
            objects[s.objectId]?.let {
                SharedWithMe(s.dto(), it.number, it.name, it.latest.version, it.latest.state, it.typeId.value)
            }
        }
    }

    /** Shares follow their object to another context, and go with it when it's deleted. */
    @EventListener(EventEnvelope::class)
    fun onObjectChange(event: EventEnvelope) {
        val id = event.payload["objectId"]?.toString()?.let(UUID::fromString) ?: return
        val tenant = TenantId(event.tenantId)
        when (event.eventType) {
            "versioning.ObjectMoved.v1" ->
                event.payload["contextId"]?.toString()?.let { store.moved(tenant, id, ContextId(UUID.fromString(it))) }
            "versioning.ObjectDeleted.v1" -> store.removeAll(tenant, id)
        }
    }
}
