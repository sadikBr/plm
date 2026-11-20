package com.plm.preferences.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.Permission
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.preferences.api.ContextPreference
import com.plm.preferences.api.EffectivePreference
import com.plm.preferences.api.PreferenceChanged
import com.plm.preferences.api.PreferenceDeclarations
import com.plm.preferences.api.PreferenceDefinition
import com.plm.preferences.api.PreferenceLevel
import com.plm.preferences.api.PreferenceService
import com.plm.preferences.api.PreferenceSource
import com.plm.preferences.api.PreferenceType
import com.plm.preferences.api.PreferenceValue
import com.plm.preferences.api.UserPreference
import com.plm.preferences.infra.PreferenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.temporal.ChronoUnit

private const val CONTEXT = "CONTEXT"
private const val USER = "USER"
private val KEY = Regex("^[a-z][A-Za-z0-9]*(\\.[a-z][A-Za-z0-9]*)+$")

/** Pure resolution: default, then contexts root first (stopping at a lock), then the user's value. */
internal object Resolver {
    fun resolve(
        definition: PreferenceDefinition,
        chain: List<Pair<ContextId, PreferenceValue?>>,
        user: PreferenceValue?,
    ): EffectivePreference {
        var effective = EffectivePreference(definition.key, definition.default, PreferenceSource.DEFAULT, null, null)
        for ((context, value) in chain) {
            if (value == null) continue
            effective =
                EffectivePreference(
                    definition.key,
                    value.value,
                    PreferenceSource.CONTEXT,
                    context,
                    if (value.locked) context else null,
                )
            if (value.locked) return effective
        }
        if (user != null && PreferenceLevel.USER in definition.levels) {
            effective = EffectivePreference(definition.key, user.value, PreferenceSource.USER, null, null)
        }
        return effective
    }
}

@Service
@Transactional
internal class DefaultPreferenceService(
    declarations: List<PreferenceDeclarations>,
    private val repository: PreferenceRepository,
    private val contexts: ContextService,
    private val access: AccessService,
    private val events: DomainEvents,
    private val clock: Clock,
) : PreferenceService {
    private val definitions: Map<String, PreferenceDefinition> =
        declarations
            .flatMap { it.preferences() }
            .onEach { require(KEY.matches(it.key)) { "Preference key '${it.key}' must look like area.name" } }
            .also { all ->
                val duplicates = all.groupBy { it.key }.filterValues { it.size > 1 }.keys
                require(duplicates.isEmpty()) { "Preferences declared twice: $duplicates" }
            }.sortedWith(compareBy({ it.category }, { it.key }))
            .associateBy { it.key }

    override fun definitions(): List<PreferenceDefinition> = definitions.values.toList()

    @Transactional(readOnly = true)
    override fun effective(
        actor: Actor,
        contextId: ContextId?,
    ): List<EffectivePreference> {
        val chain = chain(actor, contextId)
        val values = contextValues(actor, chain)
        val users = userValues(actor)
        return definitions.values.map { d ->
            Resolver.resolve(d, chain.map { it to values[it to d.key] }, users[d.key])
        }
    }

    @Transactional(readOnly = true)
    override fun value(
        actor: Actor,
        contextId: ContextId?,
        key: String,
    ): Any? = effective(actor, contextId).single { it.key == key }.value

    @Transactional(readOnly = true)
    override fun forContext(
        actor: Actor,
        contextId: ContextId,
    ): List<ContextPreference> {
        val chain = chain(actor, contextId)
        val values = contextValues(actor, chain)
        val level = if (chain.size == 1) PreferenceLevel.SITE else PreferenceLevel.CONTEXT
        val mayManage =
            if (level ==
                PreferenceLevel.SITE
            ) {
                actor.siteAdmin
            } else {
                access.has(actor, AccessTarget(contextId), Permission.MANAGE)
            }
        return definitions.values
            .filter { level in it.levels }
            .map { d ->
                val effective = Resolver.resolve(d, chain.map { it to values[it to d.key] }, null)
                val lockedAbove = effective.lockedAt != null && effective.lockedAt != contextId
                ContextPreference(d, effective, values[contextId to d.key], mayManage && !lockedAbove)
            }
    }

    override fun setForContext(
        actor: Actor,
        contextId: ContextId,
        key: String,
        value: Any?,
        locked: Boolean,
    ): ContextPreference {
        val definition = definition(key)
        val current = forContext(actor, contextId)
        val level = if (chain(actor, contextId).size == 1) PreferenceLevel.SITE else PreferenceLevel.CONTEXT
        val pref =
            current.find { it.definition.key == key }
                ?: throw ValidationException(
                    "LEVEL_NOT_ALLOWED",
                    "${definition.label} can't be set on a ${level.name.lowercase()}",
                )
        requireCanSet(actor, contextId, pref, level)
        val stored = PreferenceValue(key, validate(actor, definition, value), locked, now(), actor.principal.value)
        repository.put(actor.tenant, CONTEXT, contextId.value.toString(), stored)
        events.publish(
            actor,
            PreferenceChanged(key, CONTEXT, contextId.value.toString(), stored.value, locked, cleared = false),
        )
        return forContext(actor, contextId).single { it.definition.key == key }
    }

    override fun clearForContext(
        actor: Actor,
        contextId: ContextId,
        key: String,
    ) {
        definition(key)
        val level = if (chain(actor, contextId).size == 1) PreferenceLevel.SITE else PreferenceLevel.CONTEXT
        val pref = forContext(actor, contextId).find { it.definition.key == key } ?: return
        requireCanSet(actor, contextId, pref, level)
        if (repository.delete(actor.tenant, key, CONTEXT, contextId.value.toString()) > 0) {
            events.publish(
                actor,
                PreferenceChanged(key, CONTEXT, contextId.value.toString(), null, locked = false, cleared = true),
            )
        }
    }

    @Transactional(readOnly = true)
    override fun forUser(actor: Actor): List<UserPreference> {
        val site = repository.site(actor.tenant)
        val siteValues = contextValues(actor, listOf(site))
        val users = userValues(actor)
        return definitions.values
            .filter { PreferenceLevel.USER in it.levels }
            .map { d ->
                val effective = Resolver.resolve(d, listOf(site to siteValues[site to d.key]), users[d.key])
                UserPreference(d, effective, users[d.key], effective.lockedAt == null)
            }
    }

    override fun setForUser(
        actor: Actor,
        key: String,
        value: Any?,
    ): UserPreference {
        val definition = definition(key)
        val pref =
            forUser(actor).find { it.definition.key == key }
                ?: throw ValidationException("LEVEL_NOT_ALLOWED", "${definition.label} can't be set per user")
        if (!pref.canSet) {
            throw ConflictException(
                "PREFERENCE_LOCKED",
                "${definition.label} is locked for the whole site",
            )
        }
        val stored = PreferenceValue(key, validate(actor, definition, value), false, now(), actor.principal.value)
        repository.put(actor.tenant, USER, actor.principal.value, stored)
        events.publish(
            actor,
            PreferenceChanged(key, USER, actor.principal.value, stored.value, locked = false, cleared = false),
        )
        return forUser(actor).single { it.definition.key == key }
    }

    override fun clearForUser(
        actor: Actor,
        key: String,
    ) {
        definition(key)
        if (repository.delete(actor.tenant, key, USER, actor.principal.value) > 0) {
            events.publish(
                actor,
                PreferenceChanged(key, USER, actor.principal.value, null, locked = false, cleared = true),
            )
        }
    }

    // ---------- Helpers ----------

    private fun definition(key: String) =
        definitions[key]
            ?: throw NotFoundException("PREFERENCE_NOT_FOUND", "There is no preference '$key'", mapOf("key" to key))

    private fun requireCanSet(
        actor: Actor,
        contextId: ContextId,
        pref: ContextPreference,
        level: PreferenceLevel,
    ) {
        val lockedAt = pref.effective.lockedAt
        if (lockedAt != null && lockedAt != contextId) {
            throw ConflictException(
                "PREFERENCE_LOCKED",
                "${pref.definition.label} is locked by ${contexts.get(actor, lockedAt).name}",
                mapOf("lockedAt" to lockedAt.value),
            )
        }
        if (level == PreferenceLevel.SITE) {
            if (!actor.siteAdmin) {
                throw ForbiddenException(
                    "ADMIN_REQUIRED",
                    "Only site administrators can change site-wide preferences",
                )
            }
        } else {
            access.require(actor, AccessTarget(contextId), Permission.MANAGE)
        }
    }

    /** The context chain, root first. Unknown or invisible contexts are not found. */
    private fun chain(
        actor: Actor,
        contextId: ContextId?,
    ): List<ContextId> {
        if (contextId == null) return listOf(repository.site(actor.tenant))
        contexts.get(actor, contextId)
        return repository.chain(contextId)
    }

    private fun contextValues(
        actor: Actor,
        chain: List<ContextId>,
    ): Map<Pair<ContextId, String>, PreferenceValue> =
        repository
            .values(actor.tenant, CONTEXT, chain.map { it.value.toString() })
            .associate { (ContextId(java.util.UUID.fromString(it.scopeId)) to it.value.key) to it.value }

    private fun userValues(actor: Actor): Map<String, PreferenceValue> =
        repository.values(actor.tenant, USER, listOf(actor.principal.value)).associate { it.value.key to it.value }

    private fun validate(
        actor: Actor,
        d: PreferenceDefinition,
        value: Any?,
    ): Any {
        fun invalid(message: String): Nothing =
            throw ValidationException("INVALID_PREFERENCE_VALUE", "${d.label}: $message", mapOf("key" to d.key))
        return when (d.type) {
            PreferenceType.BOOLEAN -> value as? Boolean ?: invalid("expected true or false")
            PreferenceType.INTEGER -> {
                val n = (value as? Number)?.let { BigDecimal(it.toString()) } ?: invalid("expected a whole number")
                val i = runCatching { n.intValueExact() }.getOrElse { invalid("expected a whole number") }
                if (d.min != null && i < d.min) invalid("must be at least ${d.min}")
                if (d.max != null && i > d.max) invalid("must be at most ${d.max}")
                i
            }
            PreferenceType.STRING ->
                (value as? String)?.takeIf { it.length <= 500 }
                    ?: invalid("expected text up to 500 characters")
            PreferenceType.CHOICE -> {
                val s = value as? String ?: invalid("expected one of the options")
                if (d.options(actor).none { it.value == s }) invalid("'$s' is not one of the options")
                s
            }
        }
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
