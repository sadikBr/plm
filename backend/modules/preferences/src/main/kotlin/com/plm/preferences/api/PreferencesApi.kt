package com.plm.preferences.api

import com.plm.access.api.ContextId
import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant

/** Where a preference may be set. */
enum class PreferenceLevel {
    /** The site context: applies to everything. */
    SITE,

    /** Organizations, products, libraries, projects. */
    CONTEXT,

    /** Each user, for themselves. */
    USER,
}

enum class PreferenceType { BOOLEAN, INTEGER, STRING, CHOICE }

data class PreferenceOption(
    val value: String,
    val label: String,
)

/**
 * A preference a module offers. Declare them as Spring beans implementing [PreferenceDeclarations].
 * Keys are `area.name`, e.g. `versioning.checkInCommentRequired`.
 */
data class PreferenceDefinition(
    val key: String,
    val label: String,
    val description: String,
    val category: String,
    val type: PreferenceType,
    val default: Any?,
    val levels: Set<PreferenceLevel>,
    val min: Int? = null,
    val max: Int? = null,
    /** For CHOICE: the allowed values, computed when asked (they may come from configuration). */
    val options: (Actor) -> List<PreferenceOption> = { emptyList() },
)

/** Implemented by any module that offers preferences. */
fun interface PreferenceDeclarations {
    fun preferences(): List<PreferenceDefinition>
}

/** Where an effective value comes from. */
enum class PreferenceSource { DEFAULT, CONTEXT, USER }

data class EffectivePreference(
    val key: String,
    val value: Any?,
    val source: PreferenceSource,
    /** For CONTEXT: the context the value was set on. */
    val sourceContextId: ContextId?,
    /** The context that locked it, if any: nothing below (users included) can change it. */
    val lockedAt: ContextId?,
)

/** A value set on exactly one context or user. */
data class PreferenceValue(
    val key: String,
    val value: Any?,
    val locked: Boolean,
    val setAt: Instant,
    val setBy: String,
)

/** One preference as seen from a context: what applies there, and what is set there itself. */
data class ContextPreference(
    val definition: PreferenceDefinition,
    /** Effective for the context, ignoring user values. */
    val effective: EffectivePreference,
    val own: PreferenceValue?,
    /** Whether the actor may set it here (permission, level, and not locked above). */
    val canSet: Boolean,
)

data class UserPreference(
    val definition: PreferenceDefinition,
    val effective: EffectivePreference,
    val own: PreferenceValue?,
    val canSet: Boolean,
)

/**
 * Preferences (roadmap 1.13). Resolution for a context and user: start from the default; walk from
 * the site down to the context, each value replacing the previous one; stop at the first locked
 * value; then, if nothing was locked and users may set it, the user's own value wins.
 *
 * Setting on a context needs MANAGE there (the site: site administrators) and that no context above
 * locked it. Error codes: `PREFERENCE_NOT_FOUND`, `INVALID_PREFERENCE_VALUE`, `PREFERENCE_LOCKED`,
 * `LEVEL_NOT_ALLOWED`, `ACCESS_DENIED`, `ADMIN_REQUIRED`.
 */
interface PreferenceService {
    fun definitions(): List<PreferenceDefinition>

    /** Every preference's value for [actor] in [contextId] (or site-wide when null). */
    fun effective(
        actor: Actor,
        contextId: ContextId?,
    ): List<EffectivePreference>

    /** Typed convenience for modules: the effective value of [key]. */
    fun value(
        actor: Actor,
        contextId: ContextId?,
        key: String,
    ): Any?

    fun forContext(
        actor: Actor,
        contextId: ContextId,
    ): List<ContextPreference>

    fun setForContext(
        actor: Actor,
        contextId: ContextId,
        key: String,
        value: Any?,
        locked: Boolean,
    ): ContextPreference

    fun clearForContext(
        actor: Actor,
        contextId: ContextId,
        key: String,
    )

    fun forUser(actor: Actor): List<UserPreference>

    fun setForUser(
        actor: Actor,
        key: String,
        value: Any?,
    ): UserPreference

    fun clearForUser(
        actor: Actor,
        key: String,
    )
}

data class PreferenceChanged(
    val key: String,
    val scope: String,
    val scopeId: String,
    val value: Any?,
    val locked: Boolean,
    val cleared: Boolean,
) : DomainEvent {
    override val eventType = "preferences.PreferenceChanged.v1"
    override val aggregateType = "preference"
    override val aggregateId: java.util.UUID = java.util.UUID.nameUUIDFromBytes("preference:$key".toByteArray())
}
