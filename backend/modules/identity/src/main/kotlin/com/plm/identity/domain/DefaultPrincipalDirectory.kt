package com.plm.identity.domain

import com.plm.identity.api.Principal
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.identity.infra.PrincipalRepository
import com.plm.platform.actor.Actor
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
    private val clock: Clock,
) : PrincipalDirectory {
    override fun recordSignIn(signIn: SignIn) {
        principals.upsert(
            tenant = signIn.tenant,
            id = signIn.id,
            username = signIn.username,
            displayName = signIn.displayName?.takeIf { it.isNotBlank() } ?: signIn.username,
            email = signIn.email,
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
    override fun displayName(
        tenant: TenantId,
        id: PrincipalId,
    ): String = principals.find(tenant, id)?.displayName ?: id.value
}
