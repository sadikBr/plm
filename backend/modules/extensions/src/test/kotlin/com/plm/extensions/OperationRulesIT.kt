package com.plm.extensions

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.extensions.api.ExtensionService
import com.plm.extensions.api.OperationRuleService
import com.plm.extensions.api.OperationRuleSettings
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.CreateSubtype
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.Rename
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.io.File
import java.util.UUID

/** Roadmap 2.5: rules that refuse operations, from the site and from extensions. */
@PlmIntegrationTest
class OperationRulesIT(
    @Autowired private val rules: OperationRuleService,
    @Autowired private val extensions: ExtensionService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val types: TypeService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val manager = Actor(TenantId.DEFAULT, PrincipalId("mona-manager"))

    /** A subtype of its own, so these rules never touch other tests' parts. */
    private val typeName = "Ruled${UUID.randomUUID().toString().take(8)}"
    private val type: TypeId by lazy {
        types
            .createSubtype(
                admin,
                CreateSubtype(TypeId.PART, typeName, "Ruled part"),
            ).id
    }
    private val product: ContextId by lazy {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Rules ${UUID.randomUUID()}"),
            )
        contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Arm")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
            teams.put(admin, it, manager.principal, TeamRole.MANAGER)
        }
    }
    private var extensionId: String? = null

    @BeforeEach
    fun createType() {
        check(type.value.toString().isNotEmpty()) // rules name it before any part is made
    }

    @AfterEach
    fun cleanUp() {
        rules
            .list(admin)
            .filter {
                it.source == "site" && typeName in it.types
            }.forEach { rules.delete(admin, UUID.fromString(it.id)) }
        extensionId?.let { extensions.uninstall(admin, it) }
    }

    private fun part(attributes: Map<String, Any?> = emptyMap()) =
        versioning.create(alice, CreateVersionedObject(type, product, "Elbow", attributes = attributes))

    private fun refused(vararg messages: String) =
        ThrowingConsumer<Throwable> {
            assertThat(it).isInstanceOf(ValidationException::class.java)
            assertThat((it as PlmException).code).isEqualTo("OPERATION_REFUSED")
            messages.forEach { m -> assertThat(it.message).contains(m) }
        }

    /** The quality-rules sample, under its own id and for this test's type only. */
    private fun installQualityRules() {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "extensions/samples").isDirectory) dir = dir.parentFile
        val id = "acme.quality-${UUID.randomUUID().toString().take(8)}"
        val manifest =
            File(dir, "extensions/samples/quality-rules/plm-extension.yaml")
                .readText()
                .replace("id: acme.quality-rules", "id: $id")
                .replace("types: [Part]", "types: [$typeName]")
                .replace(
                    "    on: [object.set-state]\n",
                    "    on: [object.set-state]\n    appliesTo:\n      types: [$typeName]\n",
                ).replace(
                    "    on: [object.rename]\n",
                    "    on: [object.rename]\n    appliesTo:\n      types: [$typeName]\n",
                )
        extensions.install(admin, manifest)
        extensionId = id
    }

    @Test
    fun `an extension's rules refuse check-in, release and renaming, with their messages`() {
        installQualityRules()
        val obj = part()
        versioning.checkOut(alice, obj.id)
        assertThatThrownBy { versioning.checkIn(alice, obj.id) }.satisfies(refused("Add a description"))
        versioning.updateWorkingCopy(alice, obj.id, mapOf("description" to "A bracket for the elbow"))
        versioning.checkIn(alice, obj.id)

        versioning.setState(manager, obj.id, SetState("UNDER_REVIEW"))
        assertThatThrownBy {
            versioning.setState(manager, obj.id, SetState("RELEASED"))
        }.satisfies(refused("a comment is required"))
        val released = versioning.setState(manager, obj.id, SetState("RELEASED", "Design review DR-7"))

        assertThatThrownBy {
            versioning.rename(manager, released.id, Rename(number = "ELB-1"))
        }.satisfies(refused("keeps its number"))
        assertThat(
            versioning.rename(manager, released.id, Rename(name = "Elbow bracket")).name,
        ).isEqualTo("Elbow bracket")

        // An administrator's override isn't a normal release: rules don't apply to it.
        val other = part()
        assertThat(
            versioning.setState(admin, other.id, SetState("RELEASED", override = true)).latest.state,
        ).isEqualTo("RELEASED")
    }

    @Test
    fun `site rules refuse operations, all failing rules at once, and can be switched off`() {
        val noRevise =
            rules.define(
                admin,
                OperationRuleSettings(
                    "No revising heavy parts",
                    listOf("object.revise", "object.check-out"),
                    listOf(typeName),
                    "object.attributes.material == 'Lead'",
                    "Lead parts are frozen",
                ),
            )
        rules.define(
            admin,
            OperationRuleSettings(
                "Managers only",
                listOf("object.check-out"),
                listOf(typeName),
                "!('MANAGE' in user.permissions)",
                "Only managers edit these",
            ),
        )
        val lead = part(mapOf("material" to "Lead"))
        assertThatThrownBy {
            versioning.checkOut(alice, lead.id)
        }.satisfies(refused("Lead parts are frozen", "Only managers edit these"))
        assertThat(versioning.checkOut(manager, part(mapOf("material" to "Al")).id).iteration).isEqualTo(2)

        rules.update(
            admin,
            UUID.fromString(noRevise.id),
            OperationRuleSettings(
                noRevise.name,
                noRevise.operations,
                noRevise.types,
                noRevise.condition,
                noRevise.message,
                active = false,
            ),
        )
        assertThatThrownBy { versioning.checkOut(alice, lead.id) }.satisfies(refused("Only managers edit these"))
    }

    @Test
    fun `a rule that can't be evaluated refuses, and bad rules are refused`() {
        rules.define(
            admin,
            OperationRuleSettings(
                "Broken",
                listOf("object.create"),
                listOf(typeName),
                "object.attributes.nosuch == 1",
                "Never",
            ),
        )
        assertThatThrownBy { part() }.satisfies(refused("couldn't be checked"))

        assertThatThrownBy {
            rules.define(admin, OperationRuleSettings("Bad", listOf("object.launch"), emptyList(), "true", "x"))
        }.isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("INVALID_RULE") }
        assertThatThrownBy {
            rules.define(
                admin,
                OperationRuleSettings("Bad", listOf("object.create"), emptyList(), "object.name ==", "x"),
            )
        }.isInstanceOfSatisfying(
            ValidationException::class.java,
        ) { assertThat(it.code).isEqualTo("INVALID_EXPRESSION") }
        assertThatThrownBy { rules.list(alice) }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `extension rules are listed with their source, and go away with the extension`() {
        installQualityRules()
        val listed = rules.list(admin).filter { it.source == extensionId }
        assertThat(listed.map { it.name }).containsExactlyInAnyOrder(
            "Describe before checking in",
            "Say why it's released",
            "Released parts keep their number",
        )
        extensions.setEnabled(admin, extensionId!!, false)
        val obj = part()
        versioning.checkOut(alice, obj.id)
        assertThat(versioning.checkIn(alice, obj.id).latest.iteration).isEqualTo(2) // no description needed now
    }
}
