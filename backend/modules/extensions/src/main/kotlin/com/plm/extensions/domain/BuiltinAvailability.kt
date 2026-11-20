package com.plm.extensions.domain

import com.plm.access.api.Permission
import com.plm.extensions.api.ActionOption
import com.plm.extensions.domain.BuiltinActions.Companion.CHANGE_LIFECYCLE
import com.plm.extensions.domain.BuiltinActions.Companion.CHECK_IN
import com.plm.extensions.domain.BuiltinActions.Companion.CHECK_OUT
import com.plm.extensions.domain.BuiltinActions.Companion.RENAME
import com.plm.extensions.domain.BuiltinActions.Companion.REVISE
import com.plm.extensions.domain.BuiltinActions.Companion.SET_STATE
import com.plm.extensions.domain.BuiltinActions.Companion.UNDO_CHECK_OUT
import com.plm.platform.actor.Actor
import com.plm.versioning.api.ObjectLifecycle
import com.plm.versioning.api.VersionedObject

/** What an availability check knows about the object and the person looking at it. */
internal class ObjectSituation(
    val actor: Actor,
    val obj: VersionedObject,
    val lifecycle: ObjectLifecycle,
    val permissions: Set<Permission>,
) {
    val mine get() = obj.checkedOutBy == actor.principal.value
    val free get() = obj.checkedOutBy == null

    fun label(state: String) =
        lifecycle.template
            .state(state)
            ?.labels
            ?.let { it["en"] ?: it.values.firstOrNull() } ?: state

    val current get() = label(obj.latest.state)
}

/** Whether an action applies at all (null: don't show it), and if so why it can't be used now. */
internal sealed interface Availability {
    data object Hidden : Availability

    data class Shown(
        val reason: String? = null,
        val options: List<ActionOption> = emptyList(),
    ) : Availability
}

/**
 * The rules the built-in actions follow, the same ones the operations enforce (the server refuses
 * anyway; this explains it before the user tries). Actions without a rule are always usable.
 */
internal object BuiltinAvailability {
    fun of(
        actionId: String,
        s: ObjectSituation,
    ): Availability =
        when (actionId) {
            CHECK_OUT ->
                Availability.Shown(
                    when {
                        !s.free -> "Checked out by ${if (s.mine) "you" else "someone else"}"
                        Permission.MODIFY !in s.permissions -> "You don't have permission to edit this"
                        !s.lifecycle.editable -> "${s.current} objects can't be edited. Revise to start a new revision."
                        else -> null
                    },
                )
            CHECK_IN, UNDO_CHECK_OUT ->
                Availability.Shown(if (s.mine) null else "Only whoever checked it out can do this")
            REVISE ->
                Availability.Shown(
                    when {
                        !s.free -> "Check it in first"
                        Permission.REVISE !in s.permissions -> "You don't have permission to revise this"
                        !s.lifecycle.revisable -> {
                            val revisable =
                                s.lifecycle.template.states.filter { it.revisable }.joinToString(
                                    " or ",
                                ) { s.label(it.key) }
                            "Only ${revisable.ifEmpty { "released" }} objects can be revised (this one is ${s.current})"
                        }
                        else -> null
                    },
                )
            SET_STATE -> {
                val next = s.lifecycle.nextStates.map { ActionOption(it, s.label(it)) }
                val overrides =
                    if (s.lifecycle.canOverride) {
                        s.lifecycle.template.states
                            .map { it.key }
                            .filter { it != s.obj.latest.state && it !in s.lifecycle.nextStates }
                            .map { ActionOption(it, s.label(it), override = true) }
                    } else {
                        emptyList()
                    }
                Availability.Shown(
                    when {
                        !s.free -> "Check it in first"
                        next.isEmpty() && overrides.isEmpty() ->
                            if (Permission.PROMOTE !in s.permissions) {
                                "You don't have permission to change the state"
                            } else {
                                "No transitions from ${s.current}"
                            }
                        else -> null
                    },
                    next + overrides,
                )
            }
            RENAME ->
                Availability.Shown(
                    when {
                        Permission.MANAGE !in s.permissions -> "Only managers of this context can rename or renumber"
                        !s.free -> "Check it in first"
                        else -> null
                    },
                )
            CHANGE_LIFECYCLE ->
                if (!s.actor.siteAdmin) {
                    Availability.Hidden
                } else {
                    Availability.Shown(
                        if (!s.free) "Check it in first" else null,
                    )
                }
            else -> Availability.Shown()
        }
}
