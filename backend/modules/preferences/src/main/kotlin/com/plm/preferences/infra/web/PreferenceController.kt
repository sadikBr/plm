package com.plm.preferences.infra.web

import com.plm.access.api.ContextId
import com.plm.platform.actor.Actor
import com.plm.preferences.api.ContextPreference
import com.plm.preferences.api.EffectivePreference
import com.plm.preferences.api.PreferenceDefinition
import com.plm.preferences.api.PreferenceLevel
import com.plm.preferences.api.PreferenceOption
import com.plm.preferences.api.PreferenceService
import com.plm.preferences.api.PreferenceSource
import com.plm.preferences.api.PreferenceType
import com.plm.preferences.api.PreferenceValue
import com.plm.preferences.api.UserPreference
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
internal class PreferenceController(
    private val preferences: PreferenceService,
) {
    /** Every preference's value for the caller in a context (site-wide when omitted). */
    @GetMapping("/api/v1/preferences/effective")
    fun effective(
        actor: Actor,
        @RequestParam(required = false) contextId: UUID?,
    ) = preferences.effective(actor, contextId?.let(::ContextId)).map(::EffectiveResponse)

    /** Preferences that can be set on this context, what applies here and where it comes from. */
    @GetMapping("/api/v1/contexts/{contextId}/preferences")
    fun forContext(
        actor: Actor,
        @PathVariable contextId: UUID,
    ) = preferences.forContext(actor, ContextId(contextId)).map { ContextPreferenceResponse(it, actor) }

    /** Sets a preference here (and below). `locked` stops everything below from changing it. Needs MANAGE (site: site admin). */
    @PutMapping("/api/v1/contexts/{contextId}/preferences/{key}")
    fun setForContext(
        actor: Actor,
        @PathVariable contextId: UUID,
        @PathVariable key: String,
        @RequestBody request: SetPreferenceRequest,
    ) = ContextPreferenceResponse(
        preferences.setForContext(
            actor,
            ContextId(contextId),
            key,
            request.value,
            request.locked ?: false,
        ),
        actor,
    )

    @DeleteMapping("/api/v1/contexts/{contextId}/preferences/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun clearForContext(
        actor: Actor,
        @PathVariable contextId: UUID,
        @PathVariable key: String,
    ) = preferences.clearForContext(actor, ContextId(contextId), key)

    @GetMapping("/api/v1/me/preferences")
    fun forUser(actor: Actor) = preferences.forUser(actor).map { UserPreferenceResponse(it, actor) }

    @PutMapping("/api/v1/me/preferences/{key}")
    fun setForUser(
        actor: Actor,
        @PathVariable key: String,
        @RequestBody request: SetPreferenceRequest,
    ) = UserPreferenceResponse(preferences.setForUser(actor, key, request.value), actor)

    @DeleteMapping("/api/v1/me/preferences/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun clearForUser(
        actor: Actor,
        @PathVariable key: String,
    ) = preferences.clearForUser(actor, key)
}

internal data class SetPreferenceRequest(
    val value: Any?,
    val locked: Boolean? = null,
)

internal data class PreferenceDefinitionResponse(
    val key: String,
    val label: String,
    val description: String,
    val category: String,
    val type: PreferenceType,
    val default: Any?,
    val levels: List<PreferenceLevel>,
    val min: Int?,
    val max: Int?,
    val options: List<PreferenceOption>,
) {
    constructor(d: PreferenceDefinition, actor: Actor) : this(
        d.key,
        d.label,
        d.description,
        d.category,
        d.type,
        d.default,
        d.levels.sorted(),
        d.min,
        d.max,
        d.options(actor),
    )
}

internal data class EffectiveResponse(
    val key: String,
    val value: Any?,
    val source: PreferenceSource,
    val sourceContextId: UUID?,
    val lockedAt: UUID?,
) {
    constructor(e: EffectivePreference) : this(e.key, e.value, e.source, e.sourceContextId?.value, e.lockedAt?.value)
}

internal data class OwnValueResponse(
    val value: Any?,
    val locked: Boolean,
    val setAt: Instant,
    val setBy: String,
) {
    constructor(v: PreferenceValue) : this(v.value, v.locked, v.setAt, v.setBy)
}

internal data class ContextPreferenceResponse(
    val definition: PreferenceDefinitionResponse,
    val effective: EffectiveResponse,
    val own: OwnValueResponse?,
    val canSet: Boolean,
) {
    constructor(p: ContextPreference, actor: Actor) : this(
        PreferenceDefinitionResponse(p.definition, actor),
        EffectiveResponse(p.effective),
        p.own?.let(::OwnValueResponse),
        p.canSet,
    )
}

internal data class UserPreferenceResponse(
    val definition: PreferenceDefinitionResponse,
    val effective: EffectiveResponse,
    val own: OwnValueResponse?,
    val canSet: Boolean,
) {
    constructor(p: UserPreference, actor: Actor) : this(
        PreferenceDefinitionResponse(p.definition, actor),
        EffectiveResponse(p.effective),
        p.own?.let(::OwnValueResponse),
        p.canSet,
    )
}
