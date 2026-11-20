package com.plm.preferences.infra.web

import com.plm.platform.actor.Actor
import com.plm.preferences.api.ProfileService
import com.plm.preferences.api.ProfileSettings
import com.plm.preferences.api.UI_AREAS
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class ProfileController(
    private val profiles: ProfileService,
) {
    @GetMapping("/api/v1/profiles")
    fun list(actor: Actor) = profiles.list(actor)

    @PostMapping("/api/v1/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody settings: ProfileSettings,
    ) = profiles.create(actor, settings)

    @PutMapping("/api/v1/profiles/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody settings: ProfileSettings,
    ) = profiles.update(actor, id, settings)

    @DeleteMapping("/api/v1/profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = profiles.delete(actor, id)

    /** What the signed-in user's profiles hide. */
    @GetMapping("/api/v1/me/profile")
    fun mine(actor: Actor) = profiles.effective(actor)

    /** The parts of the interface a profile can hide. */
    @GetMapping("/api/v1/ui-areas")
    fun areas() = UI_AREAS.map { (key, label) -> UiArea(key, label) }
}

internal data class UiArea(
    val key: String,
    val label: String,
)
