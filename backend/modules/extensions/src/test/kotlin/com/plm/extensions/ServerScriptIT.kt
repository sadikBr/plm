package com.plm.extensions

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.extensions.api.ScriptPurpose
import com.plm.extensions.api.ScriptSettings
import com.plm.extensions.api.ScriptTest
import com.plm.extensions.api.ServerScriptService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.CreateSubtype
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Sandboxed server scripts (roadmap 5.14). */
@PlmIntegrationTest
internal class ServerScriptIT(
    @Autowired private val scripts: ServerScriptService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val types: TypeService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val typeName = "Scripted${UUID.randomUUID().toString().take(8)}"
    private val type: TypeId by lazy {
        types.createSubtype(admin, CreateSubtype(TypeId.PART, typeName, "Scripted part")).id
    }
    private val product: ContextId by lazy {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Scripts ${UUID.randomUUID()}"),
            )
        contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Arm")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    @BeforeEach
    fun createType() {
        check(type.value.toString().isNotEmpty())
    }

    @AfterEach
    fun cleanUp() {
        scripts.list(admin).filter { typeName in it.types }.forEach { scripts.delete(admin, it.id) }
    }

    private fun run(
        source: String,
        input: Map<String, Any?> = emptyMap(),
    ) = scripts.test(admin, null, ScriptTest(input, source, ScriptPurpose.AUTOMATION))

    @Test
    fun `numbering and validation scripts apply to their types`() {
        scripts.define(
            admin,
            ScriptSettings(
                "Arm numbers $typeName",
                ScriptPurpose.NUMBERING,
                "function number(ctx) { return 'ARM-' + ctx.name.toUpperCase().replace(/[^A-Z0-9]+/g, '-'); }",
                types = listOf(typeName),
            ),
        )
        val v =
            scripts.define(
                admin,
                ScriptSettings(
                    "Short names $typeName",
                    ScriptPurpose.VALIDATION,
                    """
                    function validate(ctx) {
                      plm.log('checking', ctx.object.number);
                      if (ctx.event.name.length > 12) return 'Arm names are 12 characters at most';
                    }
                    """.trimIndent(),
                    types = listOf(typeName),
                    operations = listOf("object.rename"),
                ),
            )
        val arm = versioning.create(alice, CreateVersionedObject(type, product, "Left arm"))
        assertThat(arm.number).isEqualTo("ARM-LEFT-ARM")
        // Other types are untouched.
        assertThat(
            versioning.create(alice, CreateVersionedObject(TypeId.PART, product, "Bolt")).number,
        ).doesNotStartWith("ARM-")

        assertThatThrownBy {
            versioning.rename(
                admin,
                arm.id,
                com.plm.versioning.api
                    .Rename(name = "Left arm, long version"),
            )
        }.hasMessageContaining("12 characters at most")
        val runs = scripts.runs(admin, v.id)
        assertThat(runs.first().ok).isTrue()
        assertThat(runs.first().logs).containsExactly("checking ARM-LEFT-ARM")
        assertThat(runs.first().trigger).isEqualTo("object.rename")
    }

    @Test
    fun `scripts are sandboxed and limited`() {
        assertThat(run("function run(ctx) { return { twice: ctx.n * 2 }; }", mapOf("n" to 21)).result).isEqualTo(
            mapOf(
                "twice" to 42,
            ),
        )
        // No Java, files or network.
        assertThat(run("function run() { return java.lang.System.exit(1); }").error).isNotNull()
        assertThat(run("function run() { return Packages.java.io.File; }").error).isNotNull()
        assertThat(run("function run() { return typeof JavaImporter; }").result).isEqualTo("undefined")
        // Endless loops stop; a script can't catch that.
        val loop = run("function run() { try { while (true) {} } catch (e) { return 'caught'; } }")
        assertThat(loop.ok).isFalse()
        assertThat(loop.error).startsWith("Stopped")
        // Memory.
        val greedy = run("function run() { var a = []; for (;;) a.push('x'.repeat(1000) + a.length); }")
        assertThat(greedy.error).startsWith("Stopped")
        // API calls, counted.
        val chatty = run("function run() { for (var i = 0; i < 100; i++) plm.find('X' + i); }")
        assertThat(chatty.error).contains("calls to plm")
        // Reading the platform as the user.
        val arm = versioning.create(admin, CreateVersionedObject(TypeId.PART, product, "Elbow"))
        assertThat(
            run("function run(ctx) { return plm.find(ctx.n).name; }", mapOf("n" to arm.number)).result,
        ).isEqualTo("Elbow")
        // Saving checks the source.
        assertThatThrownBy {
            scripts.define(
                admin,
                ScriptSettings(
                    "Broken $typeName",
                    ScriptPurpose.AUTOMATION,
                    "function nope( {",
                    types = listOf(typeName),
                ),
            )
        }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy {
            scripts.define(
                admin,
                ScriptSettings("Wrong $typeName", ScriptPurpose.AUTOMATION, "var x = 1;", types = listOf(typeName)),
            )
        }.hasMessageContaining("Define a function run")
        assertThatThrownBy { scripts.list(alice) }.hasMessageContaining("administrators")
    }
}
