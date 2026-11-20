package com.plm.preferences.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.preferences.api.EffectiveProfile
import com.plm.preferences.api.Profile
import com.plm.preferences.api.ProfileChanged
import com.plm.preferences.api.ProfileService
import com.plm.preferences.api.ProfileSettings
import com.plm.preferences.api.UI_AREAS
import com.plm.preferences.infra.ProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultProfileService(
    private val profiles: ProfileRepository,
    private val events: DomainEvents,
    private val clock: Clock,
) : ProfileService {
    @Transactional(readOnly = true)
    override fun list(actor: Actor): List<Profile> {
        requireAdmin(actor)
        return profiles.all(actor.tenant)
    }

    override fun create(
        actor: Actor,
        settings: ProfileSettings,
    ): Profile = save(actor, null, settings)

    override fun update(
        actor: Actor,
        id: UUID,
        settings: ProfileSettings,
    ): Profile = save(actor, id, settings)

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        val existing = find(actor, id)
        profiles.delete(actor.tenant, id)
        events.publish(actor, ProfileChanged(id, existing.name, "DELETED"))
    }

    @Transactional(readOnly = true)
    override fun effective(actor: Actor): EffectiveProfile {
        val mine = profiles.of(actor.tenant, actor.principal.value)
        if (mine.isEmpty()) return EffectiveProfile(emptyList(), emptySet(), emptySet())
        // Hidden only when every profile hides it: being in one more profile never shows less.
        return EffectiveProfile(
            profiles = mine.map { it.name },
            hiddenActions = mine.map { it.hiddenActions.toSet() }.reduce { a, b -> a intersect b },
            hiddenAreas = mine.map { it.hiddenAreas.toSet() }.reduce { a, b -> a intersect b },
        )
    }

    private fun save(
        actor: Actor,
        id: UUID?,
        settings: ProfileSettings,
    ): Profile {
        requireAdmin(actor)
        if (id != null) find(actor, id)
        val name = settings.name.trim()
        if (name.isEmpty() || name.length > 80) throw ValidationException("INVALID_PROFILE", "A profile needs a name")
        val unknown = settings.hiddenAreas.filter { it !in UI_AREAS }
        if (unknown.isNotEmpty()) {
            throw ValidationException("INVALID_PROFILE", "Unknown interface areas: ${unknown.joinToString()}")
        }
        if (profiles.nameTaken(actor.tenant, name, id)) {
            throw ConflictException("DUPLICATE_PROFILE", "There's already a profile named $name")
        }
        val now = clock.instant()
        val profile =
            Profile(
                id = id ?: UuidV7.generate(clock),
                name = name,
                description = settings.description?.trim()?.ifEmpty { null },
                members = settings.members.distinct(),
                hiddenActions = settings.hiddenActions.distinct(),
                hiddenAreas = settings.hiddenAreas.distinct(),
                updatedAt = now,
                updatedBy = actor.principal.value,
            )
        profiles.save(actor.tenant, profile, actor.principal.value, now)
        events.publish(actor, ProfileChanged(profile.id, name, if (id == null) "CREATED" else "UPDATED"))
        return profile
    }

    private fun find(
        actor: Actor,
        id: UUID,
    ) = profiles.find(actor.tenant, id)
        ?: throw NotFoundException("PROFILE_NOT_FOUND", "There's no such profile", mapOf("profileId" to id))

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators manage profiles")
    }
}
