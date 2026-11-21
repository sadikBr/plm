package com.plm.extensions.api

import com.plm.access.api.Permission
import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import com.plm.versioning.api.MasterId
import java.time.Instant
import java.util.UUID

/** Where an action shows. More placements (table rows, bulk, context toolbar) come with their screens. */
enum class ActionPlacement { OBJECT_TOOLBAR, OBJECT_MORE }

/**
 * Who carries an action out. BUILTIN: a platform operation the UI knows (check out, revise...).
 * CLIENT: done in the browser (copy a link). Webhooks and workflows come with custom actions (2.4).
 */
enum class ActionHandler {
    BUILTIN,
    CLIENT,

    /** Opens a web address (from an extension), with the object's details filled in. */
    URL,

    /** Calls an extension's service and shows what it answers. */
    WEBHOOK,
}

/**
 * An action as declared: by the platform (see [ActionDeclarations]) or, later, by an extension.
 * [types] are type names and include their subtypes; [states] are lifecycle state keys. Empty means
 * every type / state.
 */
data class ActionDefinition(
    /** Stable id, e.g. `object.check-out`. Extensions use `<extension id>.<action>`. */
    val id: String,
    val label: String,
    val description: String,
    /** A name from the UI's icon set, e.g. `lock-open`. */
    val icon: String?,
    val placement: ActionPlacement,
    val order: Int,
    /** Actions of one group sit together; the toolbar separates groups. */
    val group: String,
    val types: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    /** Permission needed on the object; null: none beyond reading it. */
    val requires: Permission? = null,
    /** A CEL condition over `object` and `user`; the action shows only where it's true. */
    val visibleWhen: String? = null,
    val handler: ActionHandler = ActionHandler.BUILTIN,
    /** For [ActionHandler.URL]: the address, with `${object.number}`-style placeholders. */
    val url: String? = null,
    /** Questions asked before running it. */
    val form: ActionForm? = null,
    /** `platform`, or the extension that declared it. */
    val source: String = "platform",
)

/** A module's (or extension's) contribution to the registry. Spring beans; collected at startup. */
interface ActionDeclarations {
    fun actions(): List<ActionDefinition>
}

/** An administrator's adjustments to an action. Null fields keep the declared value. */
data class ActionConfiguration(
    val hidden: Boolean = false,
    val placement: ActionPlacement? = null,
    val order: Int? = null,
    val label: String? = null,
    val types: List<String>? = null,
    val states: List<String>? = null,
    /** Empty string: no condition, even if the declaration has one. */
    val visibleWhen: String? = null,
)

/** An action in the registry: as declared, as configured, and the result. */
data class RegisteredAction(
    val declared: ActionDefinition,
    val configuration: ActionConfiguration?,
    val configuredAt: Instant?,
    val configuredBy: String?,
) {
    val hidden: Boolean get() = configuration?.hidden == true

    /** The declaration with the configuration applied. */
    val effective: ActionDefinition
        get() =
            configuration?.let {
                declared.copy(
                    placement = it.placement ?: declared.placement,
                    order = it.order ?: declared.order,
                    label = it.label ?: declared.label,
                    types = it.types ?: declared.types,
                    states = it.states ?: declared.states,
                    visibleWhen = it.visibleWhen?.let { v -> v.ifBlank { null } } ?: declared.visibleWhen,
                )
            } ?: declared
}

/** A choice inside an action, e.g. a target state of Set state. */
data class ActionOption(
    val value: String,
    val label: String,
    /** Site administrator override (ignores the lifecycle's transitions). */
    val override: Boolean = false,
)

/** An action the user sees on an object now, and whether it can be used (or why not). */
data class AvailableAction(
    val id: String,
    val label: String,
    val icon: String?,
    val placement: ActionPlacement,
    val group: String,
    val handler: ActionHandler,
    val enabled: Boolean,
    val reason: String?,
    val options: List<ActionOption> = emptyList(),
    /** For [ActionHandler.URL]: the address to open, placeholders filled in for this object. */
    val url: String? = null,
    /** `platform`, or the extension that declared it. */
    val source: String = "platform",
    val form: ActionForm? = null,
)

/** What running an action gave: shown to the user, and what the page should do next. */
data class ActionResult(
    val ok: Boolean,
    /** INFO, WARNING or ERROR. */
    val level: String,
    val message: String?,
    val refresh: Boolean = false,
    /** Go to this object... */
    val navigateToObject: java.util.UUID? = null,
    /** ...or open this address. */
    val navigateToUrl: String? = null,
    val download: ActionDownload? = null,
    /** For background actions (roadmap 3.11): the job that runs it; follow it at `/api/v1/jobs/{id}`. */
    val jobId: java.util.UUID? = null,
)

data class ActionDownload(
    val filename: String,
    val contentType: String,
    /** Base64. */
    val content: String,
)

/** One call of an extension's action, for its log. */
data class ActionInvocation(
    val id: java.util.UUID,
    val actionId: String,
    val extensionId: String,
    val objectId: java.util.UUID,
    val objectNumber: String,
    val actor: String,
    val at: java.time.Instant,
    /** OK, FAILED (the service said no), ERROR (no usable answer), TIMEOUT. */
    val status: String,
    val httpStatus: Int?,
    val durationMs: Long,
    val message: String?,
)

/** Runs actions that are carried out on the server (webhooks), and keeps their log. */
interface ActionInvocationService {
    /**
     * Runs [actionId] on the object with the form's [parameters], if the actor may use it there now
     * (`ACTION_NOT_FOUND`, `ACTION_NOT_AVAILABLE`, `INVALID_PARAMETERS`, `ACTION_NOT_INVOCABLE`).
     * A service that fails or doesn't answer gives a result with `ok = false`, not an exception.
     */
    fun invoke(
        actor: Actor,
        objectId: MasterId,
        actionId: String,
        parameters: Map<String, Any?>,
    ): ActionResult

    /** The latest calls of an extension's actions, newest first. Site administrators. */
    fun invocations(
        actor: Actor,
        extensionId: String,
        limit: Int = 50,
    ): List<ActionInvocation>
}

/** Recorded on the object, so its audit trail shows what extensions did with it. */
data class ExtensionActionInvoked(
    val objectId: java.util.UUID,
    val number: String,
    val actionId: String,
    val extensionId: String,
    val status: String,
    val message: String?,
) : DomainEvent {
    override val eventType = "extensions.ExtensionActionInvoked.v1"
    override val aggregateType = "versioned-object"
    override val aggregateId: java.util.UUID = objectId
}

interface ActionService {
    /** Every action, in display order. Site administrators only (`ADMIN_REQUIRED`). */
    fun registry(actor: Actor): List<RegisteredAction>

    /** `ACTION_NOT_FOUND`, `UNKNOWN_TYPE`, `INVALID_STATE`, `INVALID_EXPRESSION`. Site administrators only. */
    fun configure(
        actor: Actor,
        id: String,
        configuration: ActionConfiguration,
    ): RegisteredAction

    /** Back to the declared defaults. */
    fun reset(
        actor: Actor,
        id: String,
    ): RegisteredAction

    /** The actions [actor] sees on an object, in display order, with the reason for each disabled one. */
    fun available(
        actor: Actor,
        objectId: MasterId,
    ): List<AvailableAction>
}

data class ActionConfigured(
    val actionId: String,
    val configuration: ActionConfiguration?,
) : DomainEvent {
    override val eventType = "extensions.ActionConfigured.v1"
    override val aggregateType = "action"
    override val aggregateId: UUID = UUID.nameUUIDFromBytes("action:$actionId".toByteArray())
}
