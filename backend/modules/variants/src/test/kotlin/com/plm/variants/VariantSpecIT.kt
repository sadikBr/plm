package com.plm.variants

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
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.variants.api.ChoiceRef
import com.plm.variants.api.ConditionService
import com.plm.variants.api.OptionRuleService
import com.plm.variants.api.OptionService
import com.plm.variants.api.RuleKind
import com.plm.variants.api.SaveRule
import com.plm.variants.api.SaveVariantSpec
import com.plm.variants.api.VariantSpecService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Variant specifications: saved, rule-checked selections and their structures (roadmap 7.8). */
@PlmIntegrationTest
internal class VariantSpecIT(
    @Autowired private val options: OptionService,
    @Autowired private val rules: OptionRuleService,
    @Autowired private val conditions: ConditionService,
    @Autowired private val specs: VariantSpecService,
    @Autowired private val bom: BomService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    @Test
    fun `a variant specification keeps a valid selection and shows what it builds`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Spec ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val set = ConditionsIT.roverSet(options, alice, p)
        rules.addRule(
            alice,
            set.id,
            SaveRule(RuleKind.REQUIRES, ChoiceRef("MOTOR", "HT"), listOf(ChoiceRef("BATTERY", "L"))),
        )
        val part = { name: String -> versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id }
        val rover = part("Rover")
        options.assign(alice, rover.value, set.id)
        versioning.checkOut(alice, rover)
        for ((name, condition) in listOf(
            "Motor STD" to "MOTOR=STD",
            "Motor HT" to "MOTOR=HT",
            "Pack S" to "BATTERY=S",
            "Pack L" to "BATTERY=L",
            "Chassis" to null,
        )) {
            val line = bom.add(alice, rover, AddUsage(part(name).value))
            if (condition != null) conditions.setCondition(alice, rover.value, line.id, condition)
        }
        versioning.checkIn(alice, rover)

        assertThatThrownBy {
            specs.create(
                alice,
                rover.value,
                SaveVariantSpec(
                    "Cheap and fast",
                    selection =
                        mapOf(
                            "MOTOR" to "HT",
                            "BATTERY" to "S",
                        ),
                ),
            )
        }.hasMessageContaining("MOTOR=HT requires one of BATTERY=L")
        val ht =
            specs.create(
                alice,
                rover.value,
                SaveVariantSpec(
                    "Long-range high torque",
                    selection =
                        mapOf(
                            "motor" to "ht",
                            "BATTERY" to "L",
                        ),
                ),
            )
        assertThat(ht.selection).containsEntry("MOTOR", "HT")
        assertThat(ht.check.valid).isTrue()
        assertThat(
            specs
                .structure(alice, ht.id)
                .lines
                .filter { it.applies }
                .map { it.node.line.child.name },
        ).containsExactly("Motor HT", "Pack L", "Chassis")
        assertThatThrownBy {
            specs.create(
                alice,
                rover.value,
                SaveVariantSpec(
                    "Long-range high torque",
                    selection =
                        mapOf(
                            "MOTOR" to "STD",
                            "BATTERY" to "L",
                        ),
                ),
            )
        }.hasMessageContaining("already a variant specification")
        val std =
            specs.create(
                alice,
                rover.value,
                SaveVariantSpec(
                    "Base",
                    "Entry model",
                    mapOf("MOTOR" to "STD", "BATTERY" to "S"),
                    ConfigSpec(ConfigSpecType.STATE, "RELEASED"),
                ),
            )
        assertThat(specs.list(alice, rover.value).map { it.name }).containsExactly("Base", "Long-range high torque")
        assertThat(std.spec.type).isEqualTo(ConfigSpecType.STATE)

        // A rule added later shows on the saved specification.
        rules.addRule(
            alice,
            set.id,
            SaveRule(RuleKind.EXCLUDES, ChoiceRef("MOTOR", "STD"), listOf(ChoiceRef("BATTERY", "S"))),
        )
        assertThat(specs.get(alice, std.id).check.valid).isFalse()
        val fixed =
            specs.update(
                alice,
                std.id,
                SaveVariantSpec(
                    "Base",
                    selection =
                        mapOf(
                            "MOTOR" to "STD",
                            "BATTERY" to "L",
                        ),
                ),
            )
        assertThat(fixed.check.valid).isTrue()
        specs.delete(alice, std.id)
        assertThat(specs.list(alice, rover.value)).hasSize(1)
    }
}
