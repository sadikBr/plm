package com.plm.access.api

import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.types.api.TypeId
import java.time.Instant

enum class Permission(
    val bit: Int,
) {
    /** See the object and its history. */
    READ(1),

    /** Create objects in the context. */
    CREATE(2),

    /** Check out, edit and check in. */
    MODIFY(4),

    /** Start a new revision. */
    REVISE(8),

    /** Manage the context: its team and sub-contexts. */
    MANAGE(16),

    /** Change an object's lifecycle state along its lifecycle's transitions. */
    PROMOTE(32),
    ;

    companion object {
        fun fromBits(bits: Int): Set<Permission> = entries.filter { bits and it.bit != 0 }.toSet()
    }
}

/** Roles a principal can hold in a context's team. Granted permissions come from [access policies][AccessService]. */
enum class TeamRole { VIEWER, MEMBER, MANAGER }

/** What a permission check is about: an object of [typeId] in [state] inside [contextId]. */
data class AccessTarget(
    val contextId: ContextId,
    val typeId: TypeId? = null,
    val state: String? = null,
)

/** Column expressions of the row being filtered, e.g. `m.context_id`. Must be trusted code, never user input. */
data class AccessColumns(
    val contextId: String,
    val typeId: String,
    val state: String,
)

/** A SQL boolean expression plus the named parameters it uses. Combine with `and`. */
data class SqlPredicate(
    val sql: String,
    val params: Map<String, Any?>,
)

/**
 * Single choke point for authorization (ADR 0005).
 *
 * A principal's permissions on a target are the union of every [access policy] that is defined on
 * the target's context or any ancestor, applies to the target's type and state, and names a role
 * the principal holds in the target's context or any ancestor. Site administrators have every permission.
 */
interface AccessService {
    fun permissions(
        actor: Actor,
        target: AccessTarget,
    ): Set<Permission>

    fun has(
        actor: Actor,
        target: AccessTarget,
        permission: Permission,
    ): Boolean = permission in permissions(actor, target)

    /** @throws com.plm.platform.error.ForbiddenException `ACCESS_DENIED` */
    fun require(
        actor: Actor,
        target: AccessTarget,
        permission: Permission,
    )

    /**
     * A predicate that is true exactly for the rows [actor] holds [permission] on. Add it to the
     * WHERE clause of list and search queries, so filtering happens in the database (never in memory).
     */
    fun filter(
        actor: Actor,
        permission: Permission,
        columns: AccessColumns,
    ): SqlPredicate
}

data class TeamMember(
    val contextId: ContextId,
    val principalId: PrincipalId,
    val role: TeamRole,
    val addedAt: Instant,
    val addedBy: String,
)

interface TeamService {
    /** Members of exactly this context (not inherited ones). Needs READ. */
    fun members(
        actor: Actor,
        contextId: ContextId,
    ): List<TeamMember>

    /** Adds the principal, or changes their role. Needs MANAGE. */
    fun put(
        actor: Actor,
        contextId: ContextId,
        principalId: PrincipalId,
        role: TeamRole,
    ): TeamMember

    /** Needs MANAGE. @throws com.plm.platform.error.NotFoundException `MEMBER_NOT_FOUND` */
    fun remove(
        actor: Actor,
        contextId: ContextId,
        principalId: PrincipalId,
    )
}
