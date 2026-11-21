package com.plm.automation

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.automation.api.AutomationAction
import com.plm.automation.api.AutomationActionKind
import com.plm.automation.api.AutomationService
import com.plm.automation.api.AutomationSettings
import com.plm.extensions.api.ScriptPurpose
import com.plm.extensions.api.ScriptSettings
import com.plm.extensions.api.ServerScriptService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.CreateSubtype
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** No-code automations (roadmap 5.16). */
@PlmIntegrationTest
internal class AutomationIT(
    @Autowired private val automations: AutomationService,
    @Autowired private val scripts: ServerScriptService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val types: TypeService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val typeName = "Auto${UUID.randomUUID().toString().take(8)}"
    private val calls = CopyOnWriteArrayList<String>()
    private val hook =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                calls += ex.requestBody.readAllBytes().decodeToString()
                ex.sendResponseHeaders(204, -1)
                ex.close()
            }
            start()
        }

    @AfterEach
    fun cleanUp() {
        hook.stop(0)
        automations.list(admin).filter { typeName in it.types }.forEach { automations.delete(admin, it.id) }
        scripts.list(admin).filter { typeName in it.name }.forEach { scripts.delete(admin, it.id) }
    }

    private fun setUp(): Pair<TypeId, ContextId> {
        val type = types.createSubtype(admin, CreateSubtype(TypeId.PART, typeName, "Automated part")).id
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Auto ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Arm")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        return type to p
    }

    private fun waitFor(
        id: UUID,
        count: Int,
    ) = (1..100).firstNotNullOfOrNull {
        automations.runs(admin, id).takeIf { it.size >= count } ?: run {
            Thread.sleep(100)
            null
        }
    } ?: error("No run of the automation")

    @Test
    fun `an event, a condition and actions, with a run history`() {
        val (type, p) = setUp()
        val script =
            scripts.define(
                admin,
                ScriptSettings(
                    "Weigh $typeName",
                    ScriptPurpose.AUTOMATION,
                    "function run(ctx) { return { seen: ctx.object.number, to: ctx.event.to }; }",
                ),
            )
        val auto =
            automations.define(
                admin,
                AutomationSettings(
                    "Back to work $typeName",
                    "versioning.ObjectStateChanged.v1",
                    listOf(
                        AutomationAction(
                            AutomationActionKind.SET_ATTRIBUTE,
                            attribute = "description",
                            value = "Back to work after review",
                        ),
                        AutomationAction(
                            AutomationActionKind.NOTIFY,
                            recipients = listOf("owner"),
                            message = "\${object.number} went to \${event.to}",
                        ),
                        AutomationAction(
                            AutomationActionKind.WEBHOOK,
                            url = "http://127.0.0.1:${hook.address.port}/hook",
                            secret = "k",
                        ),
                        AutomationAction(AutomationActionKind.RUN_SCRIPT, scriptId = script.id),
                    ),
                    types = listOf(typeName),
                    condition = "event.from == 'UNDER_REVIEW' && event.to == 'IN_WORK'",
                ),
            )
        val part = versioning.create(alice, CreateVersionedObject(type, p, "Arm"))
        val other = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Bolt"))
        for (o in listOf(other, part)) { // the bolt isn't of its type
            versioning.setState(admin, o.id, SetState("UNDER_REVIEW"))
            versioning.setState(admin, o.id, SetState("IN_WORK"))
        }

        val run = waitFor(auto.id, 1).single()
        assertThat(run.outcome).isEqualTo("SUCCEEDED")
        assertThat(run.objectNumber).isEqualTo(part.number)
        assertThat(run.steps.map { it.ok }).containsOnly(true)
        assertThat(run.steps[1].message).contains("${part.number} went to IN_WORK")
        assertThat(run.steps[3].message).contains("\"to\":\"IN_WORK\"")
        assertThat(
            versioning.get(alice, part.id).latest.attributes["description"],
        ).isEqualTo("Back to work after review")
        assertThat(calls.single()).contains(part.number).contains("versioning.ObjectStateChanged.v1")
        // The check-in it made started nothing else, and its own events don't trigger it again.
        Thread.sleep(500)
        assertThat(automations.runs(admin, auto.id)).hasSize(1)

        assertThatThrownBy {
            automations.define(
                admin,
                AutomationSettings("Bad $typeName", "nope.Event", emptyList(), types = listOf(typeName)),
            )
        }.hasMessageContaining("Unknown event")
        assertThatThrownBy { automations.list(alice) }.hasMessageContaining("administrators")
    }

    @Test
    fun `a failing step stops the run and is recorded`() {
        val (type, p) = setUp()
        val auto =
            automations.define(
                admin,
                AutomationSettings(
                    "On create $typeName",
                    "versioning.ObjectCreated.v1",
                    listOf(
                        AutomationAction(AutomationActionKind.SET_STATE, state = "NOWHERE"),
                        AutomationAction(
                            AutomationActionKind.NOTIFY,
                            recipients = listOf("owner"),
                            message = "never sent",
                        ),
                    ),
                    types = listOf(typeName),
                ),
            )
        val part = versioning.create(alice, CreateVersionedObject(type, p, "Arm"))
        val run = waitFor(auto.id, 1).single()
        assertThat(run.outcome).isEqualTo("FAILED")
        assertThat(run.steps).hasSize(1)
        assertThat(run.steps.single().ok).isFalse()
        assertThat(versioning.get(alice, part.id).latest.state).isEqualTo("IN_WORK")
    }
}
