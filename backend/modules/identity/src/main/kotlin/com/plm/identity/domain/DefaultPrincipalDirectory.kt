package com.plm.identity.domain

import com.plm.identity.api.AdministratorKind
import com.plm.identity.api.Principal
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.PrincipalRekeyed
import com.plm.identity.api.ProvisionPrincipal
import com.plm.identity.api.SignIn
import com.plm.identity.api.SiteAdminSettings
import com.plm.identity.api.SiteAdministrator
import com.plm.identity.api.SiteAdministrators
import com.plm.identity.infra.PrincipalRepository
import com.plm.identity.infra.ServiceAccountRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional
internal class DefaultPrincipalDirectory(
    private val principals: PrincipalRepository,
    private val serviceAccounts: ServiceAccountRepository,
    private val settings: SiteAdminSettings,
    private val events: DomainEvents,
    private val clock: Clock,
) : PrincipalDirectory {
    override fun recordSignIn(signIn: SignIn) {
        // Someone the directory made before their first sign-in: they get their real id now.
        val known = principals.findByUsername(signIn.tenant, signIn.username)
        if (known != null &&
            known.id != signIn.id &&
            !known.signedIn &&
            principals.find(signIn.tenant, signIn.id) == null &&
            principals.rekey(signIn.tenant, known.id, signIn.id) == 1
        ) {
            events.publish(
                Actor(signIn.tenant, signIn.id),
                PrincipalRekeyed(known.id.value, signIn.id.value, signIn.username),
            )
        }
        principals.upsert(
            tenant = signIn.tenant,
            id = signIn.id,
            username = signIn.username,
            displayName = signIn.displayName?.takeIf { it.isNotBlank() } ?: signIn.username,
            email = signIn.email,
            siteAdmin = signIn.siteAdmin,
            now = clock.instant(),
        )
    }

    override fun provision(
        actor: Actor,
        person: ProvisionPrincipal,
    ): Principal {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators provision users")
        val username = person.username.trim()
        if (username.isEmpty() || username.length > 200) {
            throw ValidationException("INVALID_USER", "A user name must be 1-200 characters")
        }
        val id = principals.findByUsername(actor.tenant, username)?.id ?: person.id
        principals.provision(
            actor.tenant,
            id,
            username,
            person.displayName.trim().ifEmpty { username },
            person.email?.trim()?.ifEmpty { null },
            person.active,
            clock.instant(),
        )
        return get(actor, id)
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: PrincipalId,
    ): Principal =
        principals.find(actor.tenant, id)
            ?: throw NotFoundException("PRINCIPAL_NOT_FOUND", "User $id is unknown", mapOf("principalId" to id.value))

    @Transactional(readOnly = true)
    override fun findByUsername(
        actor: Actor,
        username: String,
    ): Principal? = principals.findByUsername(actor.tenant, username)

    @Transactional(readOnly = true)
    override fun siteAdministrators(actor: Actor): SiteAdministrators {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators see who administers the site",
            )
        }
        val builtIn = settings.builtInAdmin
        val users =
            principals.siteAdmins(actor.tenant).map {
                SiteAdministrator(
                    id = it.id,
                    username = it.username,
                    displayName = it.displayName,
                    kind = AdministratorKind.USER,
                    builtIn = it.username.equals(builtIn, ignoreCase = true),
                    lastSeen = it.lastSeen,
                )
            }
        val accounts =
            serviceAccounts
                .accounts(actor.tenant)
                .filter { it.siteAdmin && it.active }
                .map {
                    SiteAdministrator(
                        it.id,
                        it.username,
                        it.displayName,
                        AdministratorKind.SERVICE_ACCOUNT,
                        false,
                        null,
                    )
                }
        return SiteAdministrators(
            builtInUsername = builtIn,
            enforced = settings.onlyBuiltIn,
            administrators = users.sortedByDescending { it.builtIn } + accounts,
        )
    }

    @Transactional(readOnly = true)
    override fun displayName(
        tenant: TenantId,
        id: PrincipalId,
    ): String = principals.find(tenant, id)?.displayName ?: id.value
}
