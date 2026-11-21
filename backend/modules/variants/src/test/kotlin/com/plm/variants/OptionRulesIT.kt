package com.plm.variants

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.variants.api.ChoiceRef
import com.plm.variants.api.OptionRuleService
import com.plm.variants.api.OptionService
import com.plm.variants.api.RuleKind
import com.plm.variants.api.SaveChoice
import com.plm.variants.api.SaveOption
import com.plm.variants.api.SaveOptionSet
import com.plm.variants.api.SaveRule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Option rules and selection checks (roadmap 7.6). */
@PlmIntegrationTest
internal class OptionRulesIT(
    @Autowired private val options: OptionService,
    @Autowired private val rules: OptionRuleService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun ref(s: String) = s.split("=").let { ChoiceRef(it[0], it[1]) }

    @Test
    fun `rules say what a selection breaks and how to fix it`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Rules ${UUID.randomUUID()}"),
            )
        val lib = contexts.create(admin, CreateContext(org.id, ContextKind.LIBRARY, "Options")).id
        teams.put(admin, lib, alice.principal, TeamRole.MEMBER)
        var set = options.create(alice, lib.value, SaveOptionSet("Rover"))
        for ((code, choices) in listOf(
            "MOTOR" to listOf("STD", "HT"),
            "BATTERY" to listOf("S", "L"),
            "TYRES" to listOf("ROAD", "SNOW"),
        )) {
            set = options.addOption(alice, set.id, SaveOption(code, code.lowercase()))
            val o = set.options.single { it.code == code }
            for (c in choices) set = options.addChoice(alice, o.id, SaveChoice(c, c.lowercase()))
        }
        set = options.addOption(alice, set.id, SaveOption("HEATER", "Cabin heater", required = false))
        options.addChoice(alice, set.options.last().id, SaveChoice("YES", "Fitted"))

        rules.addRule(alice, set.id, SaveRule(RuleKind.REQUIRES, ref("MOTOR=HT"), listOf(ref("BATTERY=L"))))
        rules.addRule(
            alice,
            set.id,
            SaveRule(RuleKind.INCLUDES, ref("TYRES=SNOW"), listOf(ref("HEATER=YES")), "Cold climate pack"),
        )
        val all = rules.addRule(alice, set.id, SaveRule(RuleKind.EXCLUDES, ref("BATTERY=S"), listOf(ref("TYRES=SNOW"))))
        assertThat(all.map { it.text }).containsExactly(
            "MOTOR=HT requires one of BATTERY=L",
            "TYRES=SNOW includes HEATER=YES",
            "BATTERY=S excludes TYRES=SNOW",
        )
        assertThatThrownBy {
            rules.addRule(alice, set.id, SaveRule(RuleKind.REQUIRES, ref("MOTOR=HT"), listOf(ref("MOTOR=STD"))))
        }.hasMessageContaining("Only one choice per option")
        assertThatThrownBy {
            rules.addRule(alice, set.id, SaveRule(RuleKind.EXCLUDES, ref("MOTOR=XX"), listOf(ref("TYRES=SNOW"))))
        }.hasMessageContaining("no choice MOTOR=XX")

        // A good selection; optional options may be left out.
        assertThat(
            rules.check(alice, set.id, mapOf("MOTOR" to "STD", "BATTERY" to "S", "TYRES" to "ROAD")).valid,
        ).isTrue()
        // Missing and broken, each with its fix.
        val bad =
            rules.check(
                alice,
                set.id,
                mapOf("motor" to "ht", "BATTERY" to "S", "TYRES" to "SNOW", "WHEELS" to "X"),
            )
        assertThat(bad.valid).isFalse()
        assertThat(bad.problems.map { it.message }).containsExactlyInAnyOrder(
            "There's no option WHEELS",
            "MOTOR=HT requires one of BATTERY=L",
            "TYRES=SNOW includes HEATER=YES",
            "BATTERY=S excludes TYRES=SNOW",
        )
        assertThat(bad.problems.single { it.message.startsWith("MOTOR") }.fixes).containsExactly(ref("BATTERY=L"))
        assertThat(
            bad.problems
                .single {
                    it.message.startsWith("BATTERY=S")
                }.drop,
        ).containsExactly(ref("TYRES=SNOW"), ref("BATTERY=S"))
        assertThat(
            rules.check(alice, set.id, mapOf("MOTOR" to "STD")).problems.map {
                it.kind
            },
        ).containsExactly("MISSING", "MISSING")

        // An option a rule is about stays until the rule goes.
        val heater = options.get(alice, set.id).options.single { it.code == "HEATER" }
        assertThatThrownBy { options.removeOption(alice, heater.id) }.hasMessageContaining("rule(s)")
        rules.removeRule(alice, all.single { it.ifChoice.option == "TYRES" }.id)
        assertThat(options.removeOption(alice, heater.id).options.map { it.code }).doesNotContain("HEATER")
    }
}
