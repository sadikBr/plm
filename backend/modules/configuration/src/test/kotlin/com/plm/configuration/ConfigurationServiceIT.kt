package com.plm.configuration

import com.plm.access.api.Permission
import com.plm.access.api.TemplateContent
import com.plm.access.api.TemplatePolicy
import com.plm.configuration.api.AttributeConfig
import com.plm.configuration.api.ChangeAction.CONFLICT
import com.plm.configuration.api.ChangeAction.CREATE
import com.plm.configuration.api.ChangeAction.UNCHANGED
import com.plm.configuration.api.ChangeAction.UPDATE
import com.plm.configuration.api.ConfigBundle
import com.plm.configuration.api.ConfigurationService
import com.plm.configuration.api.ContextTemplateConfig
import com.plm.configuration.api.EnumerationConfig
import com.plm.configuration.api.EnumerationValueConfig
import com.plm.configuration.api.ImportPlan
import com.plm.configuration.api.LayoutConfig
import com.plm.configuration.api.LayoutGroupConfig
import com.plm.configuration.api.LifecycleConfig
import com.plm.configuration.api.LifecycleStateConfig
import com.plm.configuration.api.LifecycleTransitionConfig
import com.plm.configuration.api.NumberingSchemeConfig
import com.plm.configuration.api.TeamRoleConfig
import com.plm.configuration.api.TypeConfig
import com.plm.configuration.api.TypeRulesConfig
import com.plm.lifecycle.api.LifecycleService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.AttributeService
import com.plm.types.api.LayoutScreen
import com.plm.types.api.LayoutService
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class ConfigurationServiceIT(
    @Autowired private val configuration: ConfigurationService,
    @Autowired private val types: TypeService,
    @Autowired private val attributes: AttributeService,
    @Autowired private val rules: TypeRuleService,
    @Autowired private val layouts: LayoutService,
    @Autowired private val lifecycles: LifecycleService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("admin"), siteAdmin = true)

    /** What a dev environment would export: a Bolt subtype with everything around it. */
    private fun devBundle(
        s: String =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(6),
    ) = ConfigBundle(
        teamRoles = listOf(TeamRoleConfig("CHECKER_${s.uppercase()}", mapOf("en" to "Checker"))),
        enumerations =
            listOf(
                EnumerationConfig(
                    "thread$s",
                    mapOf("en" to "Thread standard"),
                    listOf(
                        EnumerationValueConfig("metric", mapOf("en" to "Metric")),
                        EnumerationValueConfig(
                            "unified",
                            mapOf(
                                "en" to "Unified",
                            ),
                        ),
                    ),
                ),
            ),
        numberingSchemes = listOf(NumberingSchemeConfig("Bolts $s", prefix = "B$s-", padding = 4)),
        lifecycles =
            listOf(
                LifecycleConfig(
                    "Quick$s",
                    mapOf("en" to "Quick"),
                    "DRAFT",
                    listOf(
                        LifecycleStateConfig("DRAFT", mapOf("en" to "Draft"), editable = true),
                        LifecycleStateConfig(
                            "DONE",
                            mapOf(
                                "en" to "Done",
                            ),
                            revisable = true,
                        ),
                    ),
                    listOf(LifecycleTransitionConfig("DRAFT", "DONE")),
                ),
            ),
        types =
            listOf(
                TypeConfig("Part", "Part", null, "PART"),
                TypeConfig(
                    name = "Bolt$s",
                    displayName = "Bolt $s",
                    parent = "Part",
                    baseClass = "PART",
                    attributes =
                        listOf(
                            AttributeConfig(
                                "thread",
                                mapOf("en" to "Thread"),
                                dataType = "ENUMERATION",
                                enumeration = "thread$s",
                                required = true,
                                defaultValue = "metric",
                            ),
                            AttributeConfig(
                                "length",
                                mapOf("en" to "Length"),
                                dataType = "QUANTITY",
                                dimension = "LENGTH",
                                displayUnit = "mm",
                                minValue = 0.toBigDecimal(),
                            ),
                        ),
                    rules = TypeRulesConfig(numberingScheme = "Bolts $s", allowManualNumber = false),
                    lifecycle = "Quick$s",
                    layouts =
                        listOf(
                            LayoutConfig(
                                "CREATE",
                                listOf(LayoutGroupConfig("bolt", mapOf("en" to "Bolt"), 2, listOf("thread", "length"))),
                            ),
                        ),
                ),
            ),
        contextTemplates =
            listOf(
                ContextTemplateConfig(
                    "Bolt product $s",
                    "PRODUCT",
                    content =
                        TemplateContent(
                            policies =
                                listOf(
                                    TemplatePolicy(
                                        "VIEWER",
                                        type = "Bolt$s",
                                        permissions = setOf(Permission.READ, Permission.MODIFY),
                                    ),
                                ),
                        ),
                ),
            ),
    )

    private fun ImportPlan.actions() = changes.associate { "${it.kind} ${it.key}" to it.action }

    @Test
    fun `a dry run shows the plan and changes nothing, then applying creates everything`() {
        val bundle = devBundle()
        val bolt = bundle.types.last().name

        val plan = configuration.import(admin, bundle, dryRun = true)

        assertThat(plan.applied).isFalse()
        assertThat(plan.changes.filter { it.action == CREATE }.map { it.kind }).containsExactlyInAnyOrder(
            "teamRole",
            "enumeration",
            "numberingScheme",
            "lifecycle",
            "type",
            "attribute",
            "attribute",
            "layout",
            "contextTemplate",
        )
        // Rules and lifecycle of the new type are set as its own overrides.
        assertThat(
            plan.changes
                .filter {
                    it.action == UPDATE
                }.map { it.kind },
        ).containsExactlyInAnyOrder("typeRules", "typeLifecycle")
        assertThat(plan.actions()).containsEntry("type Part", UNCHANGED)
        assertThat(types.list(admin).map { it.name }).doesNotContain(bolt)

        val applied = configuration.import(admin, bundle, dryRun = false)

        assertThat(applied.applied).isTrue()
        assertThat(applied.changes).isEqualTo(plan.changes) // the dry run said exactly what would happen
        val boltId = types.list(admin).single { it.name == bolt }.id
        assertThat(attributes.effective(admin, boltId).map { it.name }).contains("thread", "length")
        assertThat(rules.rules(admin, boltId).allowManualNumber).isFalse()
        assertThat(lifecycles.forType(admin, boltId).name).isEqualTo(bundle.lifecycles.single().name)
        assertThat(
            layouts
                .resolve(admin, boltId, LayoutScreen.CREATE)
                .groups
                .first()
                .attributes,
        ).containsExactly("thread", "length")
    }

    @Test
    fun `importing again changes nothing, and a changed setting is an update with a diff`() {
        val bundle = devBundle()
        configuration.import(admin, bundle, dryRun = false)

        assertThat(
            configuration
                .import(admin, bundle, dryRun = true)
                .changes
                .map { it.action }
                .toSet(),
        ).containsOnly(UNCHANGED)

        val bolt = bundle.types.last()
        val changed =
            bundle.copy(
                types =
                    bundle.types.dropLast(1) +
                        bolt.copy(
                            attributes =
                                bolt.attributes.map {
                                    if (it.name ==
                                        "length"
                                    ) {
                                        it.copy(required = true)
                                    } else {
                                        it
                                    }
                                },
                        ),
            )
        val plan = configuration.import(admin, changed, dryRun = false)

        val update = plan.changes.single { it.action == UPDATE }
        assertThat(update.key).isEqualTo("${bolt.name}.length")
        assertThat(update.details).containsExactly("required: false → true")
        assertThat(plan.applied).isTrue()
    }

    @Test
    fun `any conflict means nothing is applied, and failures don't hide the rest of the plan`() {
        val bundle = devBundle()
        configuration.import(admin, bundle, dryRun = false)
        val bolt = bundle.types.last()
        val broken =
            bundle.copy(
                teamRoles = bundle.teamRoles.map { it.copy(labels = mapOf("en" to "Checker (renamed)")) },
                types =
                    bundle.types.dropLast(1) +
                        bolt.copy(
                            attributes =
                                bolt.attributes.map {
                                    if (it.name ==
                                        "thread"
                                    ) {
                                        it.copy(dataType = "STRING", enumeration = null)
                                    } else {
                                        it
                                    }
                                } +
                                    // Rejected by the attribute service itself (invalid pattern), inside a savepoint.
                                    AttributeConfig(
                                        "code",
                                        mapOf("en" to "Code"),
                                        dataType = "STRING",
                                        pattern = "[unclosed",
                                    ) +
                                    AttributeConfig("finish", mapOf("en" to "Finish"), dataType = "STRING"),
                        ),
            )

        val plan = configuration.import(admin, broken, dryRun = false)

        assertThat(plan.applied).isFalse()
        val actions = plan.actions()
        assertThat(actions["teamRole ${bundle.teamRoles.single().key}"]).isEqualTo(UPDATE)
        assertThat(actions["attribute ${bolt.name}.thread"]).isEqualTo(CONFLICT)
        assertThat(actions["attribute ${bolt.name}.code"]).isEqualTo(CONFLICT)
        assertThat(
            plan.changes
                .single { it.key == "${bolt.name}.code" }
                .details
                .single(),
        ).contains("pattern")
        assertThat(actions["attribute ${bolt.name}.finish"]).isEqualTo(CREATE) // still evaluated after the failure
        // Nothing was applied: the role keeps its label, and no 'finish' attribute exists.
        val boltId = types.list(admin).single { it.name == bolt.name }.id
        assertThat(attributes.effective(admin, boltId).map { it.name }).doesNotContain("finish")
        assertThat(
            configuration
                .export(admin)
                .teamRoles
                .single { it.key == bundle.teamRoles.single().key }
                .labels,
        ).containsEntry("en", "Checker")
    }

    @Test
    fun `an export imports back unchanged, and exporting twice gives the same bundle`() {
        configuration.import(admin, devBundle(), dryRun = false)
        val exported = configuration.export(admin)

        assertThat(configuration.export(admin)).isEqualTo(exported)
        assertThat(exported.types.first().name).isIn("Document", "Part") // base types first
        val plan = configuration.import(admin, exported, dryRun = false)
        assertThat(plan.changes.filter { it.action != UNCHANGED }).isEmpty()
        assertThat(plan.applied).isTrue()
    }

    @Test
    fun `only site admins, and only known formats`() {
        val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
        assertThatThrownBy { configuration.export(alice) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy {
            configuration.import(alice, devBundle(), dryRun = true)
        }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { configuration.import(admin, ConfigBundle(format = "other/9"), dryRun = true) }
            .isInstanceOfSatisfying(
                ValidationException::class.java,
            ) { assertThat(it.code).isEqualTo("UNSUPPORTED_CONFIG_FORMAT") }
    }
}
