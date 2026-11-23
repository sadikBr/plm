package com.plm.identity.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import java.time.Instant
import java.util.UUID

data class Principal(
    val id: PrincipalId,
    val username: String,
    val displayName: String,
    val email: String?,
    val firstSeen: Instant,
    val lastSeen: Instant,
    /** Held the site administrator role at the latest sign-in. */
    val siteAdmin: Boolean = false,
    /** Made by the directory (roadmap 9.1). */
    val provisioned: Boolean = false,
    /** False once the directory deactivated them. */
    val active: Boolean = true,
    /** Has signed in at least once. */
    val signedIn: Boolean = true,
)

/** A person the directory pushes (SCIM, roadmap 9.1). */
data class ProvisionPrincipal(
    /** The id to use for someone not seen yet; a person already known by [username] keeps theirs. */
    val id: PrincipalId,
    val username: String,
    val displayName: String,
    val email: String?,
    val active: Boolean = true,
)

/** A provisioned person signed in for the first time and got their real id (the OIDC subject). */
data class PrincipalRekeyed(
    val previousId: String,
    val newId: String,
    val username: String,
) : DomainEvent {
    override val eventType = "identity.PrincipalRekeyed.v1"
    override val aggregateType = "principal"
    override val aggregateId: UUID = UUID.nameUUIDFromBytes(newId.toByteArray())
}

/** Profile claims from a validated identity-provider token. */
data class SignIn(
    val tenant: TenantId,
    val id: PrincipalId,
    val username: String,
    val displayName: String?,
    val email: String?,
    val siteAdmin: Boolean = false,
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

    /**
     * Everyone with site administration rights: users who held the role at their latest sign-in
     * and active service accounts allowed the admin scope. Site administrators only.
     *
     * @throws com.plm.platform.error.ForbiddenException `ADMIN_REQUIRED`
     */
    fun siteAdministrators(actor: Actor): SiteAdministrators

    /**
     * Creates or updates a person from the directory, site administrators only. Someone already
     * known by username is updated (and keeps their id); a new person gets [ProvisionPrincipal.id]
     * until their first sign-in, when they are re-keyed to their real subject.
     */
    fun provision(
        actor: Actor,
        person: ProvisionPrincipal,
    ): Principal

    /** Human-readable name for messages, falling back to the raw id for users never seen. */
    fun displayName(
        tenant: TenantId,
        id: PrincipalId,
    ): String
}

enum class AdministratorKind { USER, SERVICE_ACCOUNT }

data class SiteAdministrator(
    val id: PrincipalId,
    val username: String,
    val displayName: String,
    val kind: AdministratorKind,
    /** The built-in administrator made with the installation. */
    val builtIn: Boolean,
    val lastSeen: Instant?,
)

data class SiteAdministrators(
    /** Username of the built-in administrator (`plm.identity.built-in-admin`). */
    val builtInUsername: String,
    /** Only the built-in administrator gets site administration rights. */
    val enforced: Boolean,
    val administrators: List<SiteAdministrator>,
) {
    /** More than the one recommended site administrator. */
    val moreThanRecommended: Boolean get() = administrators.size > 1
}
