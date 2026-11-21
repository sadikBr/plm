package com.plm.versioning.api

import com.plm.platform.actor.Actor
import com.plm.types.api.TypeId

/** The operations rules can refuse (roadmap 2.5). Keys are what rules name in `on:`. */
enum class GuardedOperation(
    val key: String,
) {
    CREATE("object.create"),
    CHECK_OUT("object.check-out"),
    CHECK_IN("object.check-in"),
    REVISE("object.revise"),
    SET_STATE("object.set-state"),
    RENAME("object.rename"),
    CHANGE_LIFECYCLE("object.change-lifecycle"),
    MOVE("object.move"),
    DELETE("object.delete"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/**
 * An operation about to happen. [model] is the expression model (`object`, `user`) of the object
 * as it would be after the operation where that's known (create, check-in), as it is otherwise;
 * built only if a guard asks. [event] says what's happening: `operation`, and `from`, `to`,
 * `comment` (set state), `comment` (check-in), `number` and `name` (rename), `lifecycle`.
 */
class OperationCheck(
    val operation: GuardedOperation,
    val typeId: TypeId,
    val event: Map<String, Any?>,
    model: () -> Map<String, Any?>,
) {
    val model: Map<String, Any?> by lazy(model)
}

/** Why an operation was refused, and by which rule. */
data class Refusal(
    val rule: String,
    /** `site` or the extension that declared the rule. */
    val source: String,
    val message: String,
)

/**
 * Checks an operation before it happens and may refuse it (roadmap 2.5). Other modules implement
 * this (the extensions module's rules); versioning asks every guard and refuses the operation with
 * all their reasons (`OPERATION_REFUSED`). Guards run inside the operation's transaction, so they
 * must be quick and must not change anything.
 */
interface OperationGuard {
    fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal>
}
