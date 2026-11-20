package com.plm.extensions

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.extensions.api.ActionHandler
import com.plm.extensions.api.ActionService
import com.plm.extensions.api.ExtensionService
import com.plm.extensions.api.ExtensionStatus
import com.plm.extensions.api.InstallMode
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.io.File
import java.util.UUID

@PlmIntegrationTest
class ExtensionServiceIT(
    @Autowired private val extensions: ExtensionService,
    @Autowired private val actions: ActionService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val config = mapOf("erpUrl" to "https://erp.acme.example", "apiKey" to "s3cret")

    /** The repository's sample manifests, under a fresh id so tests don't meet each other. */
    private fun sample(
        file: String,
        id: String,
    ): String {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "extensions/samples").isDirectory) dir = dir.parentFile
        return File(dir, "extensions/samples/erp-sync/$file").readText().replace("id: acme.erp-sync", "id: $id")
    }

    private fun newId() = "acme.erp-${UUID.randomUUID().toString().take(8)}"

    private fun releasedPart(): com.plm.versioning.api.VersionedObject {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Ext ${UUID.randomUUID()}"),
            )
        val product = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Arm")).id
        teams.put(admin, product, alice.principal, TeamRole.MEMBER)
        val part =
            versioning.create(
                alice,
                CreateVersionedObject(
                    TypeId.PART,
                    product,
                    "Elbow",
                    attributes =
                        mapOf(
                            "material" to "Ti",
                        ),
                ),
            )
        return versioning.setState(admin, part.id, SetState("RELEASED", override = true))
    }

    private fun assertCode(
        code: String,
        call: ThrowingCallable,
    ) {
        assertThatThrownBy(
            call,
        ).isInstanceOfSatisfying(PlmException::class.java) { assertThat(it.code).isEqualTo(code) }
    }

    @Test
    fun `a preview says what installing would do, then install adds the action where it applies`() {
        val id = newId()
        val preview = extensions.preview(admin, sample("plm-extension.yaml", id), config)
        assertThat(preview.mode).isEqualTo(InstallMode.INSTALL)
        assertThat(preview.compatible).isTrue()
        assertThat(preview.permissionsAdded).containsExactly("ui:actions", "config:seed")
        assertThat(preview.actionsAdded).containsExactly("show-in-erp")
        assertThat(preview.canInstall).isTrue()

        val installed = extensions.install(admin, sample("plm-extension.yaml", id), config)
        assertThat(installed.status).isEqualTo(ExtensionStatus.ACTIVE)
        assertThat(installed.config).containsEntry("company", "ACME-US").doesNotContainKey("apiKey")
        assertThat(installed.secretsSet).containsExactly("apiKey")

        val part = releasedPart()
        val action = actions.available(alice, part.id).single { it.id == "$id.show-in-erp" }
        assertThat(action.handler).isEqualTo(ActionHandler.URL)
        assertThat(action.url).isEqualTo("https://erp.acme.example/items/${part.number}?company=ACME-US")
        assertThat(action.source).isEqualTo(id)

        // Not on parts that aren't released.
        val inWork = versioning.create(alice, CreateVersionedObject(TypeId.PART, part.contextId, "Wrist"))
        assertThat(actions.available(alice, inWork.id).map { it.id }).doesNotContain("$id.show-in-erp")
    }

    @Test
    fun `an upgrade shows what changes and keeps the settings, and needs a newer version`() {
        val id = newId()
        extensions.install(admin, sample("plm-extension.yaml", id), config)
        assertCode("EXTENSION_VERSION_NOT_NEWER") { extensions.preview(admin, sample("plm-extension.yaml", id)) }

        val preview = extensions.preview(admin, sample("plm-extension-1.1.yaml", id))
        assertThat(preview.mode).isEqualTo(InstallMode.UPGRADE)
        assertThat(preview.installedVersion).isEqualTo("1.0.0")
        assertThat(preview.permissionsAdded).containsExactly("objects:read")
        assertThat(preview.actionsAdded).containsExactly("erp-stock")
        assertThat(preview.configProblems).isEmpty() // the installed settings carry over

        val upgraded = extensions.install(admin, sample("plm-extension-1.1.yaml", id))
        assertThat(upgraded.manifest.version).isEqualTo("1.1.0")
        assertThat(upgraded.config["erpUrl"]).isEqualTo("https://erp.acme.example")
        assertThat(upgraded.secretsSet).containsExactly("apiKey")
    }

    @Test
    fun `settings are checked, secrets kept when not given again`() {
        val id = newId()
        assertThat(
            extensions.preview(admin, sample("plm-extension.yaml", id)).configProblems,
        ).containsExactly("ERP address is required")
        assertCode("INVALID_EXTENSION_CONFIG") { extensions.install(admin, sample("plm-extension.yaml", id)) }
        assertCode("INVALID_EXTENSION_CONFIG") {
            extensions.install(admin, sample("plm-extension.yaml", id), config + ("company" to "ACME-MARS"))
        }

        extensions.install(admin, sample("plm-extension.yaml", id), config)
        val changed =
            extensions.configure(
                admin,
                id,
                mapOf(
                    "erpUrl" to "https://erp2.acme.example",
                    "company" to "ACME-EU",
                ),
            )
        assertThat(changed.config).containsEntry("company", "ACME-EU")
        assertThat(changed.secretsSet).containsExactly("apiKey")
    }

    @Test
    fun `disabling takes its actions away, and uninstalling removes it`() {
        val id = newId()
        extensions.install(admin, sample("plm-extension.yaml", id), config)
        val part = releasedPart()
        assertThat(actions.available(alice, part.id).map { it.id }).contains("$id.show-in-erp")

        assertThat(extensions.setEnabled(admin, id, false).status).isEqualTo(ExtensionStatus.DISABLED)
        assertThat(actions.available(alice, part.id).map { it.id }).doesNotContain("$id.show-in-erp")

        extensions.setEnabled(admin, id, true)
        extensions.uninstall(admin, id)
        assertThat(extensions.list(admin).map { it.manifest.id }).doesNotContain(id)
        assertThat(actions.available(alice, part.id).map { it.id }).doesNotContain("$id.show-in-erp")
    }

    @Test
    fun `incompatible or invalid manifests are refused, with every problem listed`() {
        val id = newId()
        val incompatible = sample("plm-extension.yaml", id).replace("platform: \">=1.0 <2.0\"", "platform: \">=2.0\"")
        assertThat(extensions.preview(admin, incompatible, config).compatible).isFalse()
        assertThatThrownBy { extensions.install(admin, incompatible, config) }
            .isInstanceOfSatisfying(
                ConflictException::class.java,
            ) { assertThat(it.code).isEqualTo("EXTENSION_INCOMPATIBLE") }

        val invalid =
            sample("plm-extension.yaml", id)
                .replace("  - config:seed", "  - config:seed\n  - launch:rockets")
                .replace("?company=\${config.company}", "?key=\${config.apiKey}")
        assertThatThrownBy { extensions.preview(admin, invalid, config) }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.code).isEqualTo("INVALID_MANIFEST")
                @Suppress("UNCHECKED_CAST")
                val errors = it.details["errors"] as List<String>
                assertThat(errors)
                    .anyMatch { e ->
                        e.contains("launch:rockets")
                    }.anyMatch { e -> e.contains("secret 'apiKey'") }
            }
        assertCode("INVALID_MANIFEST") { extensions.preview(admin, "id: [not yaml") }
        assertCode("INVALID_MANIFEST") {
            extensions.preview(
                admin,
                sample("plm-extension.yaml", id).replace("label: Show in ERP", "lable: Show in ERP"),
            )
        }
    }

    @Test
    fun `extensions are for site administrators`() {
        assertThatThrownBy { extensions.list(alice) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy {
            extensions.preview(alice, sample("plm-extension.yaml", newId()))
        }.isInstanceOf(ForbiddenException::class.java)
    }
}
