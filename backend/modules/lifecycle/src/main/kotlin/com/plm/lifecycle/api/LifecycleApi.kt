package com.plm.lifecycle.api

import com.plm.platform.actor.Actor
import com.plm.types.api.TypeId
import java.util.UUID

@JvmInline
value class LifecycleTemplateId(
    val value: UUID,
) {
    override fun toString() = value.toString()

    companion object {
        /** The out-of-the-box "Basic" lifecycle. */
        val BASIC = LifecycleTemplateId(UUID.fromString("00000000-0000-7000-8000-000000000301"))
    }
}

data class LifecycleState(
    /** Stable key stored on objects, e.g. `RELEASED`. */
    val key: String,
    val labels: Map<String, String>,
    /** Objects in this state may be checked out and edited. */
    val editable: Boolean,
    /** Objects in this state may be revised (A.3 -> B.1). */
    val revisable: Boolean,
)

data class LifecycleTransition(
    val from: String,
    val to: String,
)

/** A state machine for objects of a type. States are in display order. */
data class LifecycleTemplate(
    val id: LifecycleTemplateId,
    val name: String,
    val labels: Map<String, String>,
    val initialState: String,
    val states: List<LifecycleState>,
    val transitions: List<LifecycleTransition>,
    val system: Boolean,
) {
    fun state(key: String): LifecycleState? = states.find { it.key == key }

    /** Unknown states (e.g. after the template changed) are neither editable nor revisable. */
    fun isEditable(state: String) = state(state)?.editable == true

    fun isRevisable(state: String) = state(state)?.revisable == true

    fun canTransition(
        from: String,
        to: String,
    ) = transitions.any { it.from == from && it.to == to }

    fun nextStates(from: String): List<LifecycleState> =
        states.filter { s -> transitions.any { it.from == from && it.to == s.key } }
}

data class CreateLifecycleTemplate(
    val name: String,
    val labels: Map<String, String>,
    val initialState: String,
    val states: List<LifecycleState>,
    val transitions: List<LifecycleTransition>,
)

/**
 * Lifecycle templates and their assignment to types (roadmap 1.6). Reading is open to everyone;
 * creating templates and assigning them is for site administrators.
 *
 * Error codes: `ADMIN_REQUIRED`, `LIFECYCLE_NOT_FOUND`, `INVALID_LIFECYCLE`, `DUPLICATE_LIFECYCLE_NAME`,
 * `TYPE_NOT_FOUND`.
 */
interface LifecycleService {
    fun list(actor: Actor): List<LifecycleTemplate>

    fun get(
        actor: Actor,
        id: LifecycleTemplateId,
    ): LifecycleTemplate

    /** The template a type uses: its own assignment, else the nearest ancestor's, else Basic. */
    fun forType(
        actor: Actor,
        typeId: TypeId,
    ): LifecycleTemplate

    fun assign(
        actor: Actor,
        typeId: TypeId,
        templateId: LifecycleTemplateId,
    ): LifecycleTemplate

    fun create(
        actor: Actor,
        command: CreateLifecycleTemplate,
    ): LifecycleTemplate
}
