package com.plm.change.api

import com.plm.platform.actor.Actor
import java.time.Instant

/**
 * The release gate (roadmap 5.13): while [enabled], parts and documents reach [states] only when
 * an approved change notice is resolved or a promotion request approved. A site administrator can
 * still override a state (recorded as an override in the object's history).
 */
data class ReleaseGate(
    val enabled: Boolean,
    val states: List<String>,
    val updatedAt: Instant?,
    val updatedBy: String?,
)

data class SetReleaseGate(
    val enabled: Boolean,
    val states: List<String> = listOf("RELEASED"),
)

/** Errors: `ADMIN_REQUIRED`, `INVALID_RELEASE_GATE`. */
interface ReleaseGateService {
    fun get(actor: Actor): ReleaseGate

    fun set(
        actor: Actor,
        request: SetReleaseGate,
    ): ReleaseGate
}
