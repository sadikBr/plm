package com.plm.directory.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

data class DirectoryUser(
    val id: UUID,
    /** The person's id in this system. */
    val principalId: String,
    val externalId: String?,
    val userName: String,
    val displayName: String?,
    val givenName: String?,
    val familyName: String?,
    val email: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int,
)

data class DirectoryUserInput(
    val externalId: String?,
    val userName: String,
    val displayName: String?,
    val givenName: String?,
    val familyName: String?,
    val email: String?,
    val active: Boolean,
)

data class DirectoryGroupMember(
    val userId: UUID,
    val userName: String,
)

data class DirectoryGroup(
    val id: UUID,
    val externalId: String?,
    val displayName: String,
    /** The role its members hold in the contexts its shared team is attached to. */
    val role: String,
    /** The shared team that mirrors it. */
    val teamId: UUID?,
    val members: List<DirectoryGroupMember>,
    /** Contexts its team is attached to, by name. */
    val contexts: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int,
)

data class DirectoryGroupInput(
    val externalId: String?,
    val displayName: String,
    /** User ids (SCIM resource ids); unknown ones are ignored. */
    val members: List<UUID>,
)

/** One SCIM PATCH operation, already parsed. */
data class PatchOperation(
    /** add, remove or replace. */
    val op: String,
    val path: String?,
    val value: Any?,
)

data class DirectoryStatus(
    val users: Int,
    val activeUsers: Int,
    val groups: Int,
    val lastChange: Instant?,
)

/** A page of a SCIM list. */
data class DirectoryPage<T>(
    val total: Int,
    val startIndex: Int,
    val items: List<T>,
)

/**
 * The directory (roadmap 9.1): users and groups an identity provider pushes over SCIM 2.0. Users
 * become people in the system (before they ever sign in); each group becomes a site-wide shared
 * team, managed by the directory, whose active members hold the group's role in every context the
 * team is attached to. Deactivating a user takes them off every team. Site administrators only.
 *
 * Errors: `ADMIN_REQUIRED`, `SCIM_USER_NOT_FOUND`, `SCIM_GROUP_NOT_FOUND`, `SCIM_UNIQUENESS`,
 * `SCIM_INVALID_VALUE`, `SCIM_INVALID_FILTER`, `SCIM_INVALID_PATH`.
 */
interface DirectoryService {
    fun status(actor: Actor): DirectoryStatus

    /** [filter]: a SCIM filter, `attribute eq "value"` (joined by `and`), or null. */
    fun users(
        actor: Actor,
        filter: String?,
        startIndex: Int,
        count: Int,
    ): DirectoryPage<DirectoryUser>

    fun user(
        actor: Actor,
        id: UUID,
    ): DirectoryUser

    fun createUser(
        actor: Actor,
        input: DirectoryUserInput,
    ): DirectoryUser

    fun replaceUser(
        actor: Actor,
        id: UUID,
        input: DirectoryUserInput,
    ): DirectoryUser

    fun patchUser(
        actor: Actor,
        id: UUID,
        operations: List<PatchOperation>,
    ): DirectoryUser

    fun deleteUser(
        actor: Actor,
        id: UUID,
    )

    fun groups(
        actor: Actor,
        filter: String?,
        startIndex: Int,
        count: Int,
    ): DirectoryPage<DirectoryGroup>

    fun group(
        actor: Actor,
        id: UUID,
    ): DirectoryGroup

    fun createGroup(
        actor: Actor,
        input: DirectoryGroupInput,
    ): DirectoryGroup

    fun replaceGroup(
        actor: Actor,
        id: UUID,
        input: DirectoryGroupInput,
    ): DirectoryGroup

    fun patchGroup(
        actor: Actor,
        id: UUID,
        operations: List<PatchOperation>,
    ): DirectoryGroup

    fun deleteGroup(
        actor: Actor,
        id: UUID,
    )

    /** Which role the group's members get (a team role key). */
    fun setGroupRole(
        actor: Actor,
        id: UUID,
        role: String,
    ): DirectoryGroup
}
