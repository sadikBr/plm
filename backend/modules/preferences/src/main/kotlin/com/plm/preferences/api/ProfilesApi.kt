package com.plm.preferences.api

import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

/** Parts of the interface a profile can hide. Keys are stable; the UI checks them. */
val UI_AREAS: Map<String, String> =
    linkedMapOf(
        "search" to "Search bar",
        "browse.organizations" to "Navigator: Organizations",
        "browse.products" to "Navigator: Products",
        "browse.libraries" to "Navigator: Libraries",
        "browse.projects" to "Navigator: Projects",
        "object.history" to "Object page: History tab",
        "object.audit" to "Object page: Audit tab",
        "context.team" to "Context page: Team tab",
        "context.access" to "Context page: Access tab",
        "context.preferences" to "Context page: Preferences tab",
    )

enum class ProfileMemberKind { USER, SHARED_TEAM }

data class ProfileMember(
    val kind: ProfileMemberKind,
    /** A principal id, or a shared team id. */
    val id: String,
)

data class Profile(
    val id: UUID,
    val name: String,
    val description: String?,
    val members: List<ProfileMember>,
    /** Action ids (built-in and extension actions) its members don't see. */
    val hiddenActions: List<String>,
    /** [UI_AREAS] keys its members don't see. */
    val hiddenAreas: List<String>,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class ProfileSettings(
    val name: String,
    val description: String? = null,
    val members: List<ProfileMember> = emptyList(),
    val hiddenActions: List<String> = emptyList(),
    val hiddenAreas: List<String> = emptyList(),
)

/** What the actor sees: hidden is what every one of their profiles hides. */
data class EffectiveProfile(
    /** Names of the profiles that apply; empty when none does (nothing is hidden). */
    val profiles: List<String>,
    val hiddenActions: Set<String>,
    val hiddenAreas: Set<String>,
)

/**
 * Profiles (roadmap 2.8): which actions and UI areas a group of users sees. Members are users or
 * shared teams. Someone in no profile sees everything; someone in several sees what any of them
 * shows. Profiles shape the interface; access rules still decide what anyone may do.
 *
 * Editing is for site administrators (`ADMIN_REQUIRED`). Error codes: `PROFILE_NOT_FOUND`,
 * `INVALID_PROFILE`, `DUPLICATE_PROFILE`.
 */
interface ProfileService {
    fun list(actor: Actor): List<Profile>

    fun create(
        actor: Actor,
        settings: ProfileSettings,
    ): Profile

    fun update(
        actor: Actor,
        id: UUID,
        settings: ProfileSettings,
    ): Profile

    fun delete(
        actor: Actor,
        id: UUID,
    )

    /** The actor's own. */
    fun effective(actor: Actor): EffectiveProfile
}

data class ProfileChanged(
    val profileId: UUID,
    val name: String,
    /** CREATED, UPDATED, DELETED */
    val change: String,
) : DomainEvent {
    override val eventType = "preferences.ProfileChanged.v1"
    override val aggregateType = "ui-profile"
    override val aggregateId: UUID = profileId
}
