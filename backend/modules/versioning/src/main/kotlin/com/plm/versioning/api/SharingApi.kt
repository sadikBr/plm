package com.plm.versioning.api

import java.time.Instant
import java.util.UUID

/** Who an object is shared with, and how (roadmap 3.10). */
data class Share(
    val objectId: UUID,
    val principalId: String,
    /** READ, or READ and MODIFY. */
    val permissions: List<String>,
    val note: String?,
    val expiresAt: Instant?,
    val grantedBy: String,
    val grantedAt: Instant,
)

data class ShareRequest(
    val principalId: String,
    /** Allows checking out and editing it, besides seeing it. */
    val modify: Boolean = false,
    val note: String? = null,
    val expiresAt: Instant? = null,
)

/** An object someone shared with you. */
data class SharedWithMe(
    val share: Share,
    val number: String,
    val name: String,
    val version: String,
    val state: String,
    val typeId: UUID,
)

/**
 * Ad hoc access (roadmap 3.10): one object shared with one person who isn't (or isn't enough) on
 * its context's team: to look at it, or to work on it too, optionally until a date. Its owner, its
 * context's managers and site administrators may share it. Recorded in the audit trail.
 * Errors: `OBJECT_NOT_FOUND`, `ACCESS_DENIED`, `INVALID_SHARE`, `PRINCIPAL_NOT_FOUND`.
 */
interface SharingService {
    fun shares(
        actor: com.plm.platform.actor.Actor,
        objectId: MasterId,
    ): List<Share>

    fun share(
        actor: com.plm.platform.actor.Actor,
        objectId: MasterId,
        request: ShareRequest,
    ): Share

    fun unshare(
        actor: com.plm.platform.actor.Actor,
        objectId: MasterId,
        principalId: String,
    )

    fun sharedWithMe(actor: com.plm.platform.actor.Actor): List<SharedWithMe>
}

data class ObjectShared(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    override val iterationId: UUID,
    override val version: String,
    val principalId: String,
    val permissions: List<String>,
    val expiresAt: String?,
) : VersioningEvent {
    override val eventType = "versioning.ObjectShared.v1"
}

data class ObjectUnshared(
    override val objectId: UUID,
    override val number: String,
    override val typeId: UUID,
    override val contextId: UUID,
    override val iterationId: UUID,
    override val version: String,
    val principalId: String,
) : VersioningEvent {
    override val eventType = "versioning.ObjectUnshared.v1"
}
