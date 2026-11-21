package com.plm.effectivity

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ConfigSpecType
import com.plm.change.api.AddChangeLink
import com.plm.change.api.ChangeRole
import com.plm.change.api.ChangeService
import com.plm.effectivity.api.AddEffectivity
import com.plm.effectivity.api.EffectivityKind
import com.plm.effectivity.api.EffectivityService
import com.plm.effectivity.api.EffectivityStatus
import com.plm.effectivity.api.EffectivityTarget
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

/** Effectivity set through a change notice, in effect once it's resolved (roadmap 7.3). */
@PlmIntegrationTest
internal class PendingEffectivityIT(
    @Autowired private val effectivity: EffectivityService,
    @Autowired private val changes: ChangeService,
    @Autowired private val bom: BomService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    @Test
    fun `a notice's effectivity waits for it, then closes the range it supersedes`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Pend ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MANAGER)
        val rover = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Rover")).id
        val motor = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Motor")).id
        versioning.checkOut(alice, rover)
        bom.add(alice, rover, AddUsage(motor.value))
        versioning.checkIn(alice, rover)
        versioning.setState(admin, motor, SetState("RELEASED", override = true))
        effectivity.createContext(alice, rover.value, null)
        effectivity.add(
            alice,
            motor.value,
            AddEffectivity(
                EffectivityTarget.VERSION,
                "A",
                kind = EffectivityKind.SERIAL,
                from = "1",
                endItemId = rover.value,
            ),
        )
        versioning.revise(alice, motor)

        val cn = versioning.create(alice, CreateVersionedObject(TypeId.CHANGE_NOTICE, p, "New motor from unit 100")).id
        assertThatThrownBy {
            effectivity.add(
                alice,
                motor.value,
                AddEffectivity(
                    EffectivityTarget.VERSION,
                    "B",
                    kind = EffectivityKind.SERIAL,
                    from = "100",
                    endItemId = rover.value,
                    changeId = cn.value,
                ),
            )
        }.hasMessageContaining("isn't one of")
        changes.link(alice, cn.value, AddChangeLink(motor.value, ChangeRole.RESULTING))
        val pending =
            effectivity.add(
                alice,
                motor.value,
                AddEffectivity(
                    EffectivityTarget.VERSION,
                    "B",
                    kind = EffectivityKind.SERIAL,
                    from = "100",
                    endItemId = rover.value,
                    changeId = cn.value,
                ),
            )
        assertThat(pending.status).isEqualTo(EffectivityStatus.PENDING)
        assertThat(effectivity.forChange(alice, cn.value).map { it.revision }).containsExactly("B")

        // Until the notice is resolved, unit 150 still gets A.
        val unit = { n: String ->
            bom
                .structure(
                    alice,
                    rover,
                    ConfigSpec(
                        ConfigSpecType.EFFECTIVITY,
                        effectivityKind = "SERIAL",
                        effectiveAt = n,
                        endItemId = rover.value,
                    ),
                ).nodes
                .single()
                .line.child.version
        }
        assertThat(unit("150")).isEqualTo("A.1")

        // A range that would replace all of A's stops the notice.
        val whole =
            effectivity.add(
                alice,
                motor.value,
                AddEffectivity(
                    EffectivityTarget.VERSION,
                    "B",
                    kind = EffectivityKind.DATE,
                    from = "2027-01-01",
                    changeId = cn.value,
                ),
            )
        effectivity.add(
            alice,
            motor.value,
            AddEffectivity(EffectivityTarget.VERSION, "A", kind = EffectivityKind.DATE, from = "2027-06-01"),
        )
        for (s in listOf("UNDER_REVIEW", "APPROVED", "IMPLEMENTATION")) versioning.setState(admin, cn, SetState(s))
        assertThatThrownBy {
            versioning.setState(admin, cn, SetState("RESOLVED"))
        }.hasMessageContaining("would replace all of revision A")
        effectivity.delete(alice, whole.id)

        versioning.setState(admin, cn, SetState("RESOLVED"))
        assertThat(
            effectivity
                .forPart(alice, motor.value)
                .filter {
                    it.kind == EffectivityKind.SERIAL
                }.map { "${it.revision} ${it.from}-${it.to ?: ""} ${it.status}" },
        ).containsExactly("A 1-99 ACTUAL", "B 100- ACTUAL")
        assertThat(unit("99")).isEqualTo("A.1")
        assertThat(unit("150")).startsWith("B.")
        // A closed notice sets nothing more.
        assertThatThrownBy {
            effectivity.add(
                alice,
                motor.value,
                AddEffectivity(
                    EffectivityTarget.VERSION,
                    "B",
                    kind = EffectivityKind.LOT,
                    from = "L1",
                    endItemId = rover.value,
                    changeId = cn.value,
                ),
            )
        }.hasMessageContaining("is closed")
    }
}
