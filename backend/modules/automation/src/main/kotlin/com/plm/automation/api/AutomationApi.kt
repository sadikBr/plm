package com.plm.automation.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** What an automation step does (roadmap 5.16). */
enum class AutomationActionKind {
    /** Moves the object to [AutomationAction.state]. */
    SET_STATE,

    /** Sets [AutomationAction.attribute] to [AutomationAction.value] (checked out, set, checked in). */
    SET_ATTRIBUTE,

    /** Tells [AutomationAction.recipients] (`owner`, `actor`, `user:<username>`) [AutomationAction.message]. */
    NOTIFY,

    /** Starts process template [AutomationAction.templateId] on the object. */
    START_PROCESS,

    /** POSTs the event and the object to [AutomationAction.url], signed with [AutomationAction.secret] if set. */
    WEBHOOK,

    /** Runs automation script [AutomationAction.scriptId] with the event and the object. */
    RUN_SCRIPT,
}

data class AutomationAction(
    val kind: AutomationActionKind,
    val state: String? = null,
    val attribute: String? = null,
    val value: Any? = null,
    val recipients: List<String> = emptyList(),
    val message: String? = null,
    val templateId: UUID? = null,
    val url: String? = null,
    val secret: String? = null,
    val scriptId: UUID? = null,
)

data class Automation(
    val id: UUID,
    val name: String,
    val description: String?,
    val active: Boolean,
    /** The event that triggers it, e.g. `versioning.ObjectStateChanged.v1`. */
    val event: String,
    /** Type names (subtypes included); empty: any object. */
    val types: List<String>,
    /** CEL over `event` (its payload), `object` and `user`; null or empty: always. */
    val condition: String?,
    val actions: List<AutomationAction>,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class AutomationSettings(
    val name: String,
    val event: String,
    val actions: List<AutomationAction>,
    val description: String? = null,
    val active: Boolean = true,
    val types: List<String> = emptyList(),
    val condition: String? = null,
)

data class AutomationStep(
    val kind: AutomationActionKind,
    val ok: Boolean,
    val message: String,
)

data class AutomationRun(
    val id: UUID,
    val automationId: UUID,
    val ranAt: Instant,
    val eventType: String,
    val objectId: UUID?,
    val objectNumber: String?,
    /** `SUCCEEDED` or `FAILED`. */
    val outcome: String,
    val steps: List<AutomationStep>,
    val durationMs: Int,
)

/** An event an automation can start on, for the editor. */
data class AutomationTrigger(
    val event: String,
    val label: String,
    /** Fields of `event` the condition can use. */
    val fields: List<String>,
)

/**
 * No-code automations (roadmap 5.16): when [Automation.event] happens to an object of
 * [Automation.types] and [Automation.condition] holds, the actions run in order, after the change
 * that caused the event is committed, as the `automation` principal. Changes automations make
 * don't start automations. Managing them is for site administrators (`ADMIN_REQUIRED`); errors:
 * `AUTOMATION_NOT_FOUND`, `INVALID_AUTOMATION`, `DUPLICATE_AUTOMATION_NAME`.
 */
interface AutomationService {
    fun triggers(actor: Actor): List<AutomationTrigger>

    fun list(actor: Actor): List<Automation>

    fun define(
        actor: Actor,
        settings: AutomationSettings,
    ): Automation

    fun update(
        actor: Actor,
        id: UUID,
        settings: AutomationSettings,
    ): Automation

    fun delete(
        actor: Actor,
        id: UUID,
    )

    fun runs(
        actor: Actor,
        id: UUID,
        limit: Int = 50,
    ): List<AutomationRun>
}
