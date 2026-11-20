package com.plm.extensions.infra.web

import com.plm.extensions.api.ActionConfiguration
import com.plm.extensions.api.ActionHandler
import com.plm.extensions.api.ActionOption
import com.plm.extensions.api.ActionPlacement
import com.plm.extensions.api.ActionService
import com.plm.extensions.api.AvailableAction
import com.plm.extensions.api.RegisteredAction
import com.plm.platform.actor.Actor
import com.plm.versioning.api.MasterId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
internal class ActionController(
    private val actions: ActionService,
) {
    /** The actions you see on an object, in display order; disabled ones say why. The UI draws toolbars and menus from this. */
    @GetMapping("/api/v1/objects/{id}/actions")
    fun available(
        actor: Actor,
        @PathVariable id: UUID,
    ) = actions.available(actor, MasterId(id)).map(::AvailableActionResponse)

    /** The action registry: every action as declared and as configured here. Site administrators. */
    @GetMapping("/api/v1/actions")
    fun registry(actor: Actor) = actions.registry(actor).map(::RegisteredActionResponse)

    /** Hide, move, reorder, relabel or restrict an action. Site administrators. */
    @PutMapping("/api/v1/actions/{id}/configuration")
    fun configure(
        actor: Actor,
        @PathVariable id: String,
        @RequestBody request: ActionConfigurationRequest,
    ) = RegisteredActionResponse(actions.configure(actor, id, request.toConfiguration()))

    /** Back to the action's declared defaults. */
    @DeleteMapping("/api/v1/actions/{id}/configuration")
    fun reset(
        actor: Actor,
        @PathVariable id: String,
    ) = RegisteredActionResponse(actions.reset(actor, id))
}

internal data class ActionOptionResponse(
    val value: String,
    val label: String,
    val override: Boolean,
) {
    constructor(o: ActionOption) : this(o.value, o.label, o.override)
}

internal data class AvailableActionResponse(
    val id: String,
    val label: String,
    val icon: String?,
    val placement: ActionPlacement,
    val group: String,
    val handler: ActionHandler,
    val enabled: Boolean,
    val reason: String?,
    val options: List<ActionOptionResponse>,
    /** For URL actions: the address to open. */
    val url: String?,
    /** `platform`, or the extension that added it. */
    val source: String,
) {
    constructor(a: AvailableAction) : this(
        a.id,
        a.label,
        a.icon,
        a.placement,
        a.group,
        a.handler,
        a.enabled,
        a.reason,
        a.options.map(::ActionOptionResponse),
        a.url,
        a.source,
    )
}

internal data class ActionConfigurationRequest(
    val hidden: Boolean? = null,
    val placement: ActionPlacement? = null,
    val order: Int? = null,
    val label: String? = null,
    val types: List<String>? = null,
    val states: List<String>? = null,
    val visibleWhen: String? = null,
) {
    fun toConfiguration() = ActionConfiguration(hidden ?: false, placement, order, label, types, states, visibleWhen)
}

internal data class ActionDefinitionResponse(
    val label: String,
    val description: String,
    val icon: String?,
    val placement: ActionPlacement,
    val order: Int,
    val group: String,
    val types: List<String>,
    val states: List<String>,
    val requires: String?,
    val visibleWhen: String?,
    val handler: ActionHandler,
)

internal data class RegisteredActionResponse(
    val id: String,
    val source: String,
    val hidden: Boolean,
    val declared: ActionDefinitionResponse,
    val effective: ActionDefinitionResponse,
    val configuration: ActionConfigurationRequest?,
    val configuredAt: Instant?,
    val configuredBy: String?,
) {
    constructor(r: RegisteredAction) : this(
        id = r.declared.id,
        source = r.declared.source,
        hidden = r.hidden,
        declared = definition(r.declared),
        effective = definition(r.effective),
        configuration =
            r.configuration?.let {
                ActionConfigurationRequest(
                    it.hidden,
                    it.placement,
                    it.order,
                    it.label,
                    it.types,
                    it.states,
                    it.visibleWhen,
                )
            },
        configuredAt = r.configuredAt,
        configuredBy = r.configuredBy,
    )

    private companion object {
        fun definition(d: com.plm.extensions.api.ActionDefinition) =
            ActionDefinitionResponse(
                d.label,
                d.description,
                d.icon,
                d.placement,
                d.order,
                d.group,
                d.types,
                d.states,
                d.requires?.name,
                d.visibleWhen,
                d.handler,
            )
    }
}
