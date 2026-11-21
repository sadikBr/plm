package com.plm.identity.domain

import com.plm.identity.api.AdministratorKind
import com.plm.identity.api.Principal
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.identity.api.SiteAdminSettings
import com.plm.identity.api.SiteAdministrator
import com.plm.identity.api.SiteAdministrators
import com.plm.identity.infra.PrincipalRepository
import com.plm.identity.infra.ServiceAccountRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
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
    private val clock: Clock,
) : PrincipalDirectory {
    override fun recordSignIn(signIn: SignIn) {
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
