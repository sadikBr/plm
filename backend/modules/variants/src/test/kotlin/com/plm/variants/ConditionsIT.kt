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
import com.plm.variants.api.OptionSet
import com.plm.variants.api.SaveChoice
import com.plm.variants.api.SaveOption
import com.plm.variants.api.SaveOptionSet
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** 150% structures: conditions on lines, filtered by a selection (roadmap 7.7). */
@PlmIntegrationTest
internal class ConditionsIT(
    @Autowired private val options: OptionService,
    @Autowired private val conditions: ConditionService,
    @Autowired private val bom: BomService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    internal companion object {
        /** A set with MOTOR (STD, HT) and BATTERY (S, L). */
        fun roverSet(
            options: OptionService,
            actor: Actor,
            context: ContextId,
        ): OptionSet {
            var set = options.create(actor, context.value, SaveOptionSet("Rover"))
            for ((code, choices) in listOf("MOTOR" to listOf("STD", "HT"), "BATTERY" to listOf("S", "L"))) {
                set = options.addOption(actor, set.id, SaveOption(code, code.lowercase()))
                val o = set.options.single { it.code == code }
                for (c in choices) set = options.addChoice(actor, o.id, SaveChoice(c, c.lowercase()))
            }
            return set
        }
    }

    @Test
    fun `a 150 percent structure filters to what a selection builds`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Cond ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val set = roverSet(options, alice, p)
        val part = { name: String -> versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id }
        val rover = part("Rover")
        val (std, ht, packS, packL, heater, chassis) =
            listOf(
                "Motor STD",
                "Motor HT",
                "Pack S",
                "Pack L",
                "Pack heater",
                "Chassis",
            ).map(part)
        versioning.checkOut(alice, packL)
        bom.add(alice, packL, AddUsage(heater.value))
        versioning.checkIn(alice, packL)
        versioning.checkOut(alice, rover)
        val lines = listOf(std, ht, packS, packL, chassis).associateWith { bom.add(alice, rover, AddUsage(it.value)) }

        assertThatThrownBy {
            conditions.setCondition(alice, rover.value, lines[std]!!.id, "MOTOR=STD")
        }.hasMessageContaining("isn't configurable")
        options.assign(alice, rover.value, set.id)
        conditions.setCondition(alice, rover.value, lines[std]!!.id, "MOTOR=STD")
        conditions.setCondition(alice, rover.value, lines[ht]!!.id, "motor = ht")
        conditions.setCondition(alice, rover.value, lines[packS]!!.id, "BATTERY=S and not MOTOR=HT")
        conditions.setCondition(alice, rover.value, lines[packL]!!.id, "BATTERY=L or MOTOR=HT")
        assertThatThrownBy {
            conditions.setCondition(alice, rover.value, lines[chassis]!!.id, "COLOR=RED")
        }.hasMessageContaining("has no option COLOR")
        assertThatThrownBy {
            conditions.setCondition(alice, rover.value, lines[chassis]!!.id, "MOTOR=XX")
        }.hasMessageContaining("MOTOR has no choice XX")
        versioning.checkIn(alice, rover)

        fun built(vararg s: Pair<String, String>) =
            conditions
                .configured(
                    alice,
                    rover.value,
                    mapOf(*s),
                ).lines
                .filter { it.applies }
                .map { it.node.line.child.name }

        assertThat(built("MOTOR" to "STD", "BATTERY" to "S")).containsExactly("Motor STD", "Pack S", "Chassis")
        // The heater goes with the long-range pack it's in.
        assertThat(
            built("MOTOR" to "HT", "BATTERY" to "S"),
        ).containsExactly("Motor HT", "Pack L", "Pack heater", "Chassis")
        val full = conditions.configured(alice, rover.value, mapOf("MOTOR" to "STD", "BATTERY" to "S"))
        assertThat(full.total).isEqualTo(6)
        assertThat(full.applying).isEqualTo(3)
        assertThat(full.lines.single { it.node.line.child.name == "Motor HT" }.condition).isEqualTo("motor = ht")
        assertThat(full.check!!.valid).isTrue()

        // Conditions follow the lines into the next working copy, and keep their options.
        versioning.checkOut(alice, rover)
        assertThat(conditions.conditions(alice, rover.value, workingCopy = true)).hasSize(4)
        versioning.undoCheckOut(alice, rover)
        val motor = options.get(alice, set.id).options.single { it.code == "MOTOR" }
        assertThatThrownBy { options.removeOption(alice, motor.id) }.hasMessageContaining("line condition")
        assertThat(
            conditions.forLines(alice, bom.usages(alice, MasterId(rover.value)).map { it.id }).map { it.expression },
        ).contains("BATTERY=L or MOTOR=HT")
    }

    private operator fun <T> List<T>.component6() = this[5]
}
