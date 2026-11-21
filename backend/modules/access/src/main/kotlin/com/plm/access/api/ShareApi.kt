package com.plm.access.api

import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import java.time.Instant
import java.util.UUID

/** One object shared with one person (roadmap 3.10). */
data class ObjectShare(
    val objectId: UUID,
    val principalId: PrincipalId,
    val contextId: ContextId,
    val permissions: Set<Permission>,
    val note: String?,
    val expiresAt: Instant?,
    val grantedBy: String,
    val grantedAt: Instant,
)

/**
 * Storage of ad hoc grants. [AccessService] honours them: a share's permissions are added to what
 * the context's policies give (DENY policies don't take them away: sharing is an explicit decision),
 * until it expires, and the person can see the object's context (not its other objects).
 * No authorization here: callers (the versioning module's sharing service) decide who may share.
 */
interface ObjectShareStore {
    fun shares(
        tenant: TenantId,
        objectId: UUID,
    ): List<ObjectShare>

    /** What's shared with this person, newest first. */
    fun sharedWith(
        tenant: TenantId,
        principal: PrincipalId,
    ): List<ObjectShare>

    fun put(
        tenant: TenantId,
        share: ObjectShare,
    )

    fun remove(
        tenant: TenantId,
        objectId: UUID,
        principal: PrincipalId,
    ): Boolean

    /** When an object moves to another context. */
    fun moved(
        tenant: TenantId,
        objectId: UUID,
        contextId: ContextId,
    )

    fun removeAll(
        tenant: TenantId,
        objectId: UUID,
    )
}
