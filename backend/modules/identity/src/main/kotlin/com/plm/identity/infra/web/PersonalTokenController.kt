package com.plm.identity.infra.web

import com.plm.identity.api.PersonalTokenService
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

internal data class IssuePersonalToken(
    val name: String,
    val scopes: List<String> = listOf("read", "write"),
    /** Null: never expires. */
    val expiresInDays: Int? = 90,
)

/** The caller's own access tokens (roadmap 8.3). */
@RestController
internal class PersonalTokenController(
    private val tokens: PersonalTokenService,
) {
    @GetMapping("/api/v1/me/tokens")
    fun list(actor: Actor) = tokens.list(actor).map(::ApiTokenResponse)

    @PostMapping("/api/v1/me/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(
        actor: Actor,
        @RequestBody request: IssuePersonalToken,
    ): IssuedTokenResponse {
        val issued =
            tokens.issue(
                actor,
                request.name,
                request.scopes,
                request.expiresInDays?.let {
                    Duration.ofDays(it.toLong())
                },
            )
        return IssuedTokenResponse(ApiTokenResponse(issued.token), issued.secret)
    }

    @DeleteMapping("/api/v1/me/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        actor: Actor,
        @PathVariable id: UUID,
    ) = tokens.revoke(actor, id)
}
