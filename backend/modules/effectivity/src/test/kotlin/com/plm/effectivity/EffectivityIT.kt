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
import com.plm.effectivity.api.AddEffectivity
import com.plm.effectivity.api.EffectivityKind
import com.plm.effectivity.api.EffectivityService
import com.plm.effectivity.api.EffectivityTarget
import com.plm.effectivity.api.SetEffectivity
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Effectivity on versions and lines, and in configuration specs (roadmap 7.1, 7.2). */
@PlmIntegrationTest
internal class EffectivityIT(
    @Autowired private val effectivity: EffectivityService,
    @Autowired private val bom: BomService,
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
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Eff ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun part(
        p: ContextId,
        name: String,
    ) = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id

    private fun release(id: MasterId) = versioning.setState(admin, id, SetState("RELEASED", override = true))

    private fun bySerial(
        endItem: MasterId,
        serial: String,
    ) = ConfigSpec(
        ConfigSpecType.EFFECTIVITY,
        effectivityKind = "SERIAL",
        effectiveAt = serial,
        endItemId = endItem.value,
    )

    private fun lines(
        root: MasterId,
        spec: ConfigSpec,
    ) = bom.structure(alice, root, spec, depth = 1).nodes.map { "${it.line.child.name}@${it.line.child.version}" }

    @Test
    fun `serial effectivity picks the revision and the lines built for each unit`() {
        val p = product()
        val rover = part(p, "Rover")
        val motor = part(p, "Motor")
        val bracket = part(p, "Bracket")
        val cable = part(p, "Cable")
        versioning.checkOut(alice, rover)
        bom.add(alice, rover, AddUsage(motor.value))
        bom.add(alice, rover, AddUsage(bracket.value))
        val cableLine = bom.add(alice, rover, AddUsage(cable.value))
        versioning.checkIn(alice, rover)
        release(motor)
        versioning.revise(alice, motor)
        release(bracket)

        // Rover is the end item serials count in.
        assertThatThrownBy {
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
        }.hasMessageContaining("isn't an end item")
        effectivity.createContext(alice, rover.value, "Rover R2 production units")
        assertThat(effectivity.contexts(alice).map { it.endItem.name }).contains("Rover")

        // Motor A for units 1 to 99, B from 100 on.
        effectivity.add(
            alice,
            motor.value,
            AddEffectivity(
                EffectivityTarget.VERSION,
                "A",
                kind = EffectivityKind.SERIAL,
                from = "1",
                to = "99",
                endItemId = rover.value,
            ),
        )
        val b =
            effectivity.add(
                alice,
                motor.value,
                AddEffectivity(
                    EffectivityTarget.VERSION,
                    "B",
                    kind = EffectivityKind.SERIAL,
                    from = "100",
                    endItemId = rover.value,
                ),
            )
        assertThatThrownBy {
            effectivity.add(
                alice,
                motor.value,
                AddEffectivity(
                    EffectivityTarget.VERSION,
                    "B",
                    kind = EffectivityKind.SERIAL,
                    from = "90",
                    to = "95",
                    endItemId = rover.value,
                ),
            )
        }.hasMessageContaining("overlaps revision A")
        assertThatThrownBy {
            effectivity.update(alice, b.id, SetEffectivity(EffectivityKind.SERIAL, "99", endItemId = rover.value))
        }.hasMessageContaining("overlaps")
        assertThatThrownBy {
            effectivity.add(
                alice,
                motor.value,
                AddEffectivity(
                    EffectivityTarget.VERSION,
                    "B",
                    kind = EffectivityKind.SERIAL,
                    from = "x",
                    endItemId = rover.value,
                ),
            )
        }.hasMessageContaining("isn't a serial number")

        // The cable only from unit 120 on: a line's effectivity, set on the working copy.
        versioning.checkOut(alice, rover)
        val wcCable = bom.usages(alice, rover, workingCopy = true).single { it.child.id == cable.value }
        assertThatThrownBy {
            effectivity.add(
                alice,
                rover.value,
                AddEffectivity(
                    EffectivityTarget.LINE,
                    lineId = cableLine.id,
                    kind = EffectivityKind.SERIAL,
                    from = "120",
                    endItemId = rover.value,
                ),
            )
        }.hasMessageContaining("isn't on your working copy")
        effectivity.add(
            alice,
            rover.value,
            AddEffectivity(
                EffectivityTarget.LINE,
                lineId = wcCable.id,
                kind = EffectivityKind.SERIAL,
                from = "120",
                endItemId = rover.value,
            ),
        )
        versioning.checkIn(alice, rover)

        assertThat(lines(rover, bySerial(rover, "50"))).containsExactly("Motor@A.1", "Bracket@A.1")
        assertThat(lines(rover, bySerial(rover, "100"))).containsExactly("Motor@B.1", "Bracket@A.1")
        assertThat(lines(rover, bySerial(rover, "150"))).containsExactly("Motor@B.1", "Bracket@A.1", "Cable@A.1")
        // Without a spec, every line, latest versions.
        assertThat(lines(rover, ConfigSpec.LATEST)).hasSize(3)

        // The line's effectivity follows it into the next working copy.
        versioning.checkOut(alice, rover)
        assertThat(
            effectivity
                .forPart(alice, rover.value, workingCopy = true)
                .filter {
                    it.target ==
                        EffectivityTarget.LINE
                }.map { it.from },
        ).containsExactly("120")
        versioning.undoCheckOut(alice, rover)
        assertThat(effectivity.forPart(alice, motor.value).map { "${it.revision} ${it.from}-${it.to ?: ""}" })
            .containsExactly("A 1-99", "B 100-")

        // Where-used follows it too: unit 50's rover doesn't use the cable.
        val spec50 = bySerial(rover, "50")
        assertThat(bom.whereUsed(alice, cable, spec50).nodes).isEmpty()
        assertThat(
            bom.whereUsed(alice, cable, bySerial(rover, "150")).nodes.map { it.parent.name },
        ).containsExactly("Rover")
    }

    @Test
    fun `date effectivity needs no end item, and bad specs are refused`() {
        val p = product()
        val top = part(p, "Top")
        val seal = part(p, "Seal")
        versioning.checkOut(alice, top)
        bom.add(alice, top, AddUsage(seal.value))
        versioning.checkIn(alice, top)
        release(seal)
        versioning.revise(alice, seal)
        effectivity.add(
            alice,
            seal.value,
            AddEffectivity(
                EffectivityTarget.VERSION,
                "A",
                kind = EffectivityKind.DATE,
                from = "2026-01-01",
                to = "2027-06-30",
            ),
        )
        effectivity.add(
            alice,
            seal.value,
            AddEffectivity(EffectivityTarget.VERSION, "B", kind = EffectivityKind.DATE, from = "2027-07-01"),
        )
        val byDate = { d: String -> ConfigSpec(ConfigSpecType.EFFECTIVITY, effectivityKind = "DATE", effectiveAt = d) }
        assertThat(lines(top, byDate("2027-06-30"))).containsExactly("Seal@A.1")
        assertThat(lines(top, byDate("2027-07-01"))).containsExactly("Seal@B.1")
        // Before any range: no version of the seal is effective.
        assertThat(
            bom
                .structure(alice, top, byDate("2025-01-01"))
                .nodes
                .single()
                .line.child.version,
        ).isNull()

        assertThatThrownBy { lines(top, byDate("soon")) }.hasMessageContaining("isn't a date")
        assertThatThrownBy {
            lines(top, ConfigSpec(ConfigSpecType.EFFECTIVITY, effectivityKind = "SERIAL", effectiveAt = "5"))
        }.hasMessageContaining("end item")
        assertThatThrownBy {
            effectivity.add(
                alice,
                seal.value,
                AddEffectivity(EffectivityTarget.VERSION, "C", kind = EffectivityKind.DATE, from = "2028-01-01"),
            )
        }.hasMessageContaining("has no revision C")
        assertThatThrownBy {
            effectivity.add(
                alice,
                seal.value,
                AddEffectivity(
                    EffectivityTarget.VERSION,
                    "B",
                    kind = EffectivityKind.DATE,
                    from = "2028-01-01",
                    to = "2027-01-01",
                ),
            )
        }.hasMessageContaining("ends before it starts")
    }
}
