package com.plm.identity.infra.web

import com.plm.identity.api.Principal
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SiteAdministrator
import com.plm.identity.api.SiteAdministrators
import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.platform.id.PrincipalId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
internal class PrincipalController(
    private val directory: PrincipalDirectory,
) {
    /** The signed-in user. */
    @GetMapping("/api/v1/me")
    fun me(actor: Actor) = MeResponse(PrincipalResponse(directory.get(actor, actor.principal)), actor.siteAdmin)

    @GetMapping("/api/v1/principals/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: String,
    ) = PrincipalResponse(directory.get(actor, PrincipalId(id)))

    /** Everyone with site administration rights; one, the built-in administrator, is recommended. */
    @GetMapping("/api/v1/site-administrators")
    fun siteAdministrators(actor: Actor) = SiteAdministratorsResponse(directory.siteAdministrators(actor))

    /** Look up a user by username, e.g. to add them to a team. */
    @GetMapping("/api/v1/principals", params = ["username"])
    fun byUsername(
        actor: Actor,
        @RequestParam username: String,
    ) = PrincipalResponse(
        directory.findByUsername(actor, username)
            ?: throw NotFoundException(
                "PRINCIPAL_NOT_FOUND",
                "No user '$username' has signed in yet",
                mapOf("username" to username),
            ),
    )
}

internal data class PrincipalResponse(
    val id: String,
    val username: String,
    val displayName: String,
    val email: String?,
    val lastSeen: Instant,
) {
    constructor(p: Principal) : this(p.id.value, p.username, p.displayName, p.email, p.lastSeen)
}

internal data class MeResponse(
    val user: PrincipalResponse,
    val siteAdmin: Boolean,
)

internal data class SiteAdministratorResponse(
    val id: String,
    val username: String,
    val displayName: String,
    val kind: String,
    val builtIn: Boolean,
    val lastSeen: Instant?,
) {
    constructor(a: SiteAdministrator) : this(a.id.value, a.username, a.displayName, a.kind.name, a.builtIn, a.lastSeen)
}

internal data class SiteAdministratorsResponse(
    val builtInUsername: String,
    val enforced: Boolean,
    val moreThanRecommended: Boolean,
    val administrators: List<SiteAdministratorResponse>,
) {
    constructor(s: SiteAdministrators) : this(
        s.builtInUsername,
        s.enforced,
        s.moreThanRecommended,
        s.administrators.map(::SiteAdministratorResponse),
    )
}
