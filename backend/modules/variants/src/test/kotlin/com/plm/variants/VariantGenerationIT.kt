package com.plm.variants

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.variants.api.ConditionService
import com.plm.variants.api.OptionService
import com.plm.variants.api.SaveVariantSpec
import com.plm.variants.api.VariantGenerationService
import com.plm.variants.api.VariantSpecService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** Generating variant parts level by level, reusing earlier ones (roadmap 7.9). */
@PlmIntegrationTest
internal class VariantGenerationIT(
    @Autowired private val options: OptionService,
    @Autowired private val conditions: ConditionService,
    @Autowired private val specs: VariantSpecService,
    @Autowired private val generation: VariantGenerationService,
    @Autowired private val bom: BomService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    @Test
    fun `variants are generated level by level, and sub-assembly variants reused`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Gen ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val set = ConditionsIT.roverSet(options, alice, p)
        val part = { name: String -> versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id }

        fun structure(
            parent: MasterId,
            vararg lines: Pair<MasterId, String?>,
        ) {
            options.assign(alice, parent.value, set.id)
            versioning.checkOut(alice, parent)
            for ((child, condition) in lines) {
                val line = bom.add(alice, parent, AddUsage(child.value, BigDecimal(2)))
                if (condition != null) conditions.setCondition(alice, parent.value, line.id, condition)
            }
            versioning.checkIn(alice, parent)
        }
        val power = part("Power unit")
        structure(
            power,
            part("Controller STD") to "MOTOR=STD",
            part("Controller HT") to "MOTOR=HT",
            part("Harness") to null,
        )
        val rover = part("Rover")
        val chassis = part("Chassis")
        structure(rover, power to null, part("Pack S") to "BATTERY=S", part("Pack L") to "BATTERY=L", chassis to null)

        val expedition =
            specs.create(
                alice,
                rover.value,
                SaveVariantSpec(
                    "Expedition",
                    selection =
                        mapOf(
                            "MOTOR" to "HT",
                            "BATTERY" to "L",
                        ),
                ),
            )
        val first = generation.generate(alice, expedition.id)
        assertThat(first.created).isEqualTo(2)
        assertThat(first.reused).isZero()
        assertThat(first.top.name).isEqualTo("Rover – Expedition")
        val powerHt = first.parts.single { it.baseId == power.value }
        assertThat(powerHt.name).isEqualTo("Power unit – HT")
        val lines = bom.usages(alice, MasterId(first.top.id)).map { "${it.child.name} x${it.quantity.toInt()}" }
        assertThat(lines).containsExactly("Power unit – HT x2", "Pack L x2", "Chassis x2")
        assertThat(
            bom.usages(alice, MasterId(powerHt.id)).map { it.child.name },
        ).containsExactly("Controller HT", "Harness")

        // Another battery, same motor: a new top, the same power unit variant.
        val rally =
            specs.create(
                alice,
                rover.value,
                SaveVariantSpec(
                    "Rally",
                    selection =
                        mapOf(
                            "MOTOR" to "HT",
                            "BATTERY" to "S",
                        ),
                ),
            )
        val second = generation.generate(alice, rally.id)
        assertThat(second.created).isEqualTo(1)
        assertThat(second.reused).isEqualTo(1)
        assertThat(
            bom.usages(alice, MasterId(second.top.id)).map {
                it.child.id
            },
        ).contains(powerHt.id).doesNotContain(chassis.value.let { UUID(0, 0) })
        assertThat(
            bom.usages(alice, MasterId(second.top.id)).map {
                it.child.name
            },
        ).containsExactly("Power unit – HT", "Pack S", "Chassis")

        // The same specification again: everything reused.
        assertThat(generation.generate(alice, expedition.id).let { it.created to it.top.id }).isEqualTo(
            0 to first.top.id,
        )

        // Variants remember where they came from.
        val origin = generation.origin(alice, first.top.id)!!
        assertThat(origin.baseNumber).isEqualTo(versioning.get(alice, rover).number)
        assertThat(origin.specName).isEqualTo("Expedition")
        assertThat(origin.selection).containsEntry("BATTERY", "L")
        assertThat(generation.origin(alice, powerHt.id)!!.selection).containsExactlyEntriesOf(mapOf("MOTOR" to "HT"))
        assertThat(generation.variantsOf(alice, rover.value).map { it.specName }).containsExactly("Expedition", "Rally")
        assertThat(generation.origin(alice, chassis.value)).isNull()
    }
}
