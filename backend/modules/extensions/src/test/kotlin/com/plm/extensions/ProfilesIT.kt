package com.plm.extensions

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.SharedTeamService
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.extensions.api.ActionService
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.preferences.api.ProfileMember
import com.plm.preferences.api.ProfileMemberKind
import com.plm.preferences.api.ProfileService
import com.plm.preferences.api.ProfileSettings
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Profiles (roadmap 2.8): what a group of users sees, and the actions it's offered. */
@PlmIntegrationTest
class ProfilesIT(
    @Autowired private val profiles: ProfileService,
    @Autowired private val actions: ActionService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val sharedTeams: SharedTeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)

    // People of this test only: profiles are site-wide, other tests' users must not be in them.
    private val viewer = Actor(TenantId.DEFAULT, PrincipalId("viewer-${UUID.randomUUID()}"))
    private val designer = Actor(TenantId.DEFAULT, PrincipalId("designer-${UUID.randomUUID()}"))
    private val created = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() = created.forEach { profiles.delete(admin, it) }

    private fun profile(
        name: String,
        members: List<ProfileMember>,
        actions: List<String> = emptyList(),
        areas: List<String> = emptyList(),
    ) = profiles
        .create(admin, ProfileSettings("$name ${UUID.randomUUID()}", null, members, actions, areas))
        .also { created += it.id }

    private fun part(): com.plm.versioning.api.MasterId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Prof ${UUID.randomUUID()}"),
            )
        val product = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, product, viewer.principal, TeamRole.MEMBER)
        teams.put(admin, product, designer.principal, TeamRole.MEMBER)
        return versioning
            .create(
                admin,
                CreateVersionedObject(
                    TypeId.PART,
                    product,
                    "Wheel",
                    attributes =
                        mapOf(
                            "material" to "Al",
                        ),
                ),
            ).id
    }

    @Test
    fun `someone in no profile sees everything`() {
        val mine = profiles.effective(viewer)
        assertThat(mine.profiles).isEmpty()
        assertThat(mine.hiddenActions).isEmpty()
        assertThat(mine.hiddenAreas).isEmpty()
    }

    @Test
    fun `a profile hides actions from the toolbar, for its members only`() {
        val id = part()
        profile(
            "Viewers",
            listOf(ProfileMember(ProfileMemberKind.USER, viewer.principal.value)),
            actions = listOf("object.check-out", "object.revise"),
            areas = listOf("object.history", "browse.libraries"),
        )

        assertThat(actions.available(viewer, id).map { it.id }).doesNotContain("object.check-out", "object.revise")
        assertThat(actions.available(designer, id).map { it.id }).contains("object.check-out", "object.revise")
        assertThat(
            profiles.effective(viewer).hiddenAreas,
        ).containsExactlyInAnyOrder("object.history", "browse.libraries")
    }

    @Test
    fun `members come through shared teams, and several profiles show what any of them shows`() {
        val team = sharedTeams.create(admin, "Reviewers ${UUID.randomUUID()}", null)
        sharedTeams.putMember(admin, team.id, designer.principal, TeamRole.MEMBER)
        profile(
            "Through team",
            listOf(ProfileMember(ProfileMemberKind.SHARED_TEAM, team.id.value.toString())),
            areas = listOf("search", "object.audit"),
        )
        profile(
            "Direct",
            listOf(ProfileMember(ProfileMemberKind.USER, designer.principal.value)),
            areas = listOf("object.audit", "context.access"),
        )

        val mine = profiles.effective(designer)
        assertThat(mine.profiles).hasSize(2)
        // Only what both hide stays hidden.
        assertThat(mine.hiddenAreas).containsExactly("object.audit")
    }

    @Test
    fun `only site administrators edit profiles, and areas must be known`() {
        val refused = runCatching { profiles.list(viewer) }.exceptionOrNull() as PlmException
        assertThat(refused.code).isEqualTo("ADMIN_REQUIRED")
        val unknown =
            runCatching {
                profile(
                    "Bad",
                    emptyList(),
                    areas = listOf("cockpit"),
                )
            }.exceptionOrNull() as PlmException
        assertThat(unknown.code).isEqualTo("INVALID_PROFILE")
    }
}
