package com.plm.change

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.change.api.AddChangeLink
import com.plm.change.api.ChangeRole
import com.plm.change.api.ChangeService
import com.plm.change.api.ReleaseGateService
import com.plm.change.api.SetReleaseGate
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** The release gate (roadmap 5.13). */
@PlmIntegrationTest
internal class ReleaseGateIT(
    @Autowired private val gate: ReleaseGateService,
    @Autowired private val changes: ChangeService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Gate ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MANAGER)
        }
    }

    @Test
    fun `parts reach Released only through a change notice, unless an administrator overrides`() {
        assertThat(gate.get(alice).enabled).isTrue()
        val p = product()
        val bracket = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Bracket")).id
        val drawing = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, p, "Drawing")).id
        versioning.setState(alice, bracket, SetState("UNDER_REVIEW"))
        // Other states are free; Released is not, even for an administrator moving it normally.
        assertThatThrownBy { versioning.setState(alice, bracket, SetState("RELEASED")) }
            .hasMessageContaining("change notice or promotion request")
        assertThatThrownBy { versioning.setState(admin, bracket, SetState("RELEASED")) }
            .hasMessageContaining("change notice")
        // A change object isn't gated (its own lifecycle).
        val cn = versioning.create(alice, CreateVersionedObject(TypeId.CHANGE_NOTICE, p, "Release the bracket")).id
        changes.link(alice, cn.value, AddChangeLink(bracket.value, ChangeRole.RESULTING))
        listOf("UNDER_REVIEW", "APPROVED", "IMPLEMENTATION", "RESOLVED").forEach {
            versioning.setState(alice, cn, SetState(it))
        }
        assertThat(versioning.get(alice, bracket).latest.state).isEqualTo("RELEASED")

        // The override stays possible, and is recorded as one.
        versioning.setState(admin, drawing, SetState("RELEASED", "Legacy drawing", override = true))
        assertThat(versioning.get(alice, drawing).latest.state).isEqualTo("RELEASED")

        // Turned off, anyone allowed may release.
        assertThatThrownBy { gate.set(alice, SetReleaseGate(false)) }.hasMessageContaining("administrators")
        gate.set(admin, SetReleaseGate(false))
        try {
            val other = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Pin")).id
            versioning.setState(alice, other, SetState("UNDER_REVIEW"))
            versioning.setState(alice, other, SetState("RELEASED"))
            assertThat(versioning.get(alice, other).latest.state).isEqualTo("RELEASED")
        } finally {
            gate.set(admin, SetReleaseGate(true))
        }
    }
}
