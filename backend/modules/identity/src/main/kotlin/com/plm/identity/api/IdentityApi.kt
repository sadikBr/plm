package com.plm.identity.api

import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import java.time.Instant

data class Principal(
    val id: PrincipalId,
    val username: String,
    val displayName: String,
    val email: String?,
    val firstSeen: Instant,
    val lastSeen: Instant,
)

/** Profile claims from a validated identity-provider token. */
data class SignIn(
    val tenant: TenantId,
    val id: PrincipalId,
    val username: String,
    val displayName: String?,
    val email: String?,
)

interface PrincipalDirectory {
    /** Creates or refreshes the directory entry. Called by the web layer on authenticated requests. */
    fun recordSignIn(signIn: SignIn)

    /** @throws com.plm.platform.error.NotFoundException `PRINCIPAL_NOT_FOUND` */
    fun get(
        actor: Actor,
        id: PrincipalId,
    ): Principal

    fun findByUsername(
        actor: Actor,
        username: String,
    ): Principal?

    /** Human-readable name for messages, falling back to the raw id for users never seen. */
    fun displayName(
        tenant: TenantId,
        id: PrincipalId,
    ): String
}
