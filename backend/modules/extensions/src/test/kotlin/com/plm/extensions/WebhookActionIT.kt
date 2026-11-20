package com.plm.extensions

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.extensions.api.ActionHandler
import com.plm.extensions.api.ActionInvocationService
import com.plm.extensions.api.ActionService
import com.plm.extensions.api.ExtensionService
import com.plm.identity.api.ServiceAccountService
import com.plm.identity.api.TokenAuthenticator
import com.plm.identity.api.TokenKind
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Roadmap 2.4: actions that call the extension's service, signed, with forms and results. */
@PlmIntegrationTest
class WebhookActionIT(
    @Autowired private val extensions: ExtensionService,
    @Autowired private val actions: ActionService,
    @Autowired private val invocations: ActionInvocationService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val tokens: TokenAuthenticator,
    @Autowired private val serviceAccounts: ServiceAccountService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val json = JsonMapper.builder().build()
    private val received = CopyOnWriteArrayList<Triple<String, Map<String, String>, String>>()
    private lateinit var erp: HttpServer
    private lateinit var id: String

    /** A stand-in ERP: answers like the sample's mock, and records what it was sent. */
    @BeforeEach
    fun startErp() {
        erp = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        erp.createContext("/") { ex ->
            val body = ex.requestBody.readAllBytes().decodeToString()
            received +=
                Triple(
                    ex.requestURI.toString(),
                    ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() },
                    body,
                )
            val call = json.readValue(body, Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val params = call["parameters"] as Map<String, Any?>
            val (status, answer) =
                when {
                    ex.requestURI.path == "/slow" -> Thread.sleep(2_500).let { 200 to "{}" }
                    ex.requestURI.path.startsWith("/report") ->
                        200 to
                            """{"message":"Report","download":{"filename":"cost.csv","contentType":"text/csv","content":"${Base64.getEncoder().encodeToString(
                                "a,b\n".toByteArray(),
                            )}"}}"""
                    (params["quantity"] as Number).toInt() > 100 ->
                        409 to
                            """{"message":"Plant can't make more than 100"}"""
                    else -> 200 to """{"message":"Order SO-1 created at ${params["plant"]}","refresh":true}"""
                }
            ex.responseHeaders.add("Content-Type", "application/json")
            val bytes = answer.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        erp.start()
        id = "acme.erp-${UUID.randomUUID().toString().take(8)}"
        extensions.install(admin, manifest(), config())
    }

    @AfterEach
    fun stopErp() = erp.stop(0)

    private fun config() =
        mapOf(
            "erpUrl" to "https://erp.acme.example",
            "erpApiUrl" to "http://127.0.0.1:${erp.address.port}",
            "apiKey" to "demo-key",
        )

    private fun manifest(): String {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "extensions/samples").isDirectory) dir = dir.parentFile
        return File(
            dir,
            "extensions/samples/erp-sync/plm-extension-2.0.yaml",
        ).readText().replace("id: acme.erp-sync", "id: $id")
    }

    private fun releasedPart(): VersionedObject {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Hook ${UUID.randomUUID()}"),
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

    private fun code(call: () -> Unit) = runCatching(call).exceptionOrNull().let { (it as PlmException).code }

    @Test
    fun `sends a signed request with the object and the answers, and shows the service's answer`() {
        val part = releasedPart()
        val action = actions.available(alice, part.id).single { it.id == "$id.send-to-erp" }
        assertThat(action.handler).isEqualTo(ActionHandler.WEBHOOK)
        assertThat(action.form!!.properties.keys).containsExactly("plant", "quantity", "urgent")

        val result = invocations.invoke(alice, part.id, "$id.send-to-erp", mapOf("plant" to "P200", "quantity" to "5"))
        assertThat(result.ok).isTrue()
        assertThat(result.message).isEqualTo("Order SO-1 created at P200")
        assertThat(result.refresh).isTrue()

        val (path, headers, body) = received.single()
        assertThat(path).isEqualTo("/send?company=ACME-US")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("demo-key".toByteArray(), "HmacSHA256")) }
        val expected =
            "sha256=" + HexFormat.of().formatHex(mac.doFinal("${headers["x-plm-timestamp"]}.$body".toByteArray()))
        assertThat(headers["x-plm-signature"]).isEqualTo(expected)
        val sent = json.readValue(body, Map::class.java)
        assertThat(sent["parameters"]).isEqualTo(
            mapOf("plant" to "P200", "quantity" to 5, "urgent" to null).filterValues {
                it !=
                    null
            },
        )
        assertThat((sent["object"] as Map<*, *>)["number"]).isEqualTo(part.number)
        assertThat((sent["user"] as Map<*, *>)["id"]).isEqualTo("alice")

        // api:read: a token acting as alice, read-only, never administration, for a few minutes.
        val api = sent["api"] as Map<*, *>
        assertThat(api["scopes"]).isEqualTo(listOf("read"))
        assertThat(api["baseUrl"]).isEqualTo("http://localhost:8080")
        val delegated = tokens.authenticate(api["token"] as String)!!
        assertThat(delegated.principal).isEqualTo(alice.principal)
        assertThat(delegated.kind).isEqualTo(TokenKind.DELEGATED)
        assertThat(delegated.extensionId).isEqualTo(id)
        assertThat(delegated.siteAdmin).isFalse()
        assertThat(Instant.parse(api["expiresAt"] as String)).isBefore(Instant.now().plusSeconds(16 * 60))

        val logged = invocations.invocations(admin, id).single()
        assertThat(logged.status).isEqualTo("OK")
        assertThat(logged.objectNumber).isEqualTo(part.number)
        assertThat(logged.httpStatus).isEqualTo(200)
    }

    @Test
    fun `the form's answers are checked before anything is sent`() {
        val part = releasedPart()
        assertThatThrownBy {
            invocations.invoke(
                alice,
                part.id,
                "$id.send-to-erp",
                mapOf(
                    "quantity" to 0,
                    "plant" to "P999",
                ),
            )
        }.isInstanceOfSatisfying(PlmException::class.java) {
            assertThat(it.code).isEqualTo("INVALID_PARAMETERS")
            assertThat(
                it.message,
            ).contains("Plant must be one of P100, P200").contains("Quantity must be at least 1.0")
        }
        assertThat(received).isEmpty()
    }

    @Test
    fun `a refusal or a timeout is a failed result, logged, not an error`() {
        val part = releasedPart()
        val refused = invocations.invoke(alice, part.id, "$id.send-to-erp", mapOf("plant" to "P100", "quantity" to 500))
        assertThat(refused.ok).isFalse()
        assertThat(refused.message).isEqualTo("Plant can't make more than 100")
        assertThat(invocations.invocations(admin, id).first().status).isEqualTo("FAILED")

        // Version 2.1 of the extension gives the report 1 s, and this ERP takes 2.5 s.
        val impatient =
            manifest()
                .replace("version: 2.0.0", "version: 2.1.0")
                .replace(
                    "url: \"\${config.erpApiUrl}/report\"\n        secret: apiKey",
                    "url: \"\${config.erpApiUrl}/slow\"\n        secret: apiKey\n        timeoutSeconds: 1",
                )
        extensions.install(admin, impatient)
        val slow = invocations.invoke(alice, part.id, "$id.erp-report", emptyMap())
        assertThat(slow.ok).isFalse()
        assertThat(slow.message).isEqualTo("ERP sync didn't answer within 1 s")
        assertThat(invocations.invocations(admin, id).first().status).isEqualTo("TIMEOUT")
    }

    @Test
    fun `a service can hand back a file`() {
        val part = releasedPart()
        val result = invocations.invoke(alice, part.id, "$id.erp-report", emptyMap())
        assertThat(result.download!!.filename).isEqualTo("cost.csv")
        assertThat(Base64.getDecoder().decode(result.download.content).decodeToString()).isEqualTo("a,b\n")
    }

    @Test
    fun `an action that doesn't apply to the object can't be run`() {
        val part = releasedPart()
        val inWork = versioning.create(alice, CreateVersionedObject(TypeId.PART, part.contextId, "Wrist"))
        assertThat(
            code { invocations.invoke(alice, inWork.id, "$id.send-to-erp", emptyMap()) },
        ).isEqualTo("ACTION_NOT_FOUND")
        assertThat(
            code {
                invocations.invoke(alice, part.id, "object.check-out", emptyMap())
            },
        ).isEqualTo("ACTION_NOT_INVOCABLE")
        extensions.setEnabled(admin, id, false)
        assertThat(
            code {
                invocations.invoke(alice, part.id, "$id.send-to-erp", mapOf("plant" to "P100"))
            },
        ).isEqualTo("ACTION_NOT_FOUND")
    }

    @Test
    fun `api account gives the extension a service account, off while it's disabled or uninstalled`() {
        val account = serviceAccounts.list(admin).single { it.extensionId == id }
        assertThat(account.active).isTrue()
        assertThat(account.username).startsWith("svc-ext-")

        extensions.setEnabled(admin, id, false)
        assertThat(serviceAccounts.list(admin).single { it.extensionId == id }.active).isFalse()
        extensions.setEnabled(admin, id, true)
        assertThat(serviceAccounts.list(admin).single { it.extensionId == id }.active).isTrue()
        extensions.uninstall(admin, id)
        assertThat(serviceAccounts.list(admin).single { it.extensionId == id }.active).isFalse()
    }

    @Test
    fun `a webhook needs its permission and a secret setting to sign with`() {
        val bad =
            manifest()
                .replace(
                    "  - webhooks:call\n",
                    "",
                ).replace("secret: apiKey\n        timeoutSeconds: 10", "secret: company")
        assertThatThrownBy { extensions.preview(admin, bad.replace("version: 2.0.0", "version: 9.0.0")) }
            .isInstanceOfSatisfying(PlmException::class.java) {
                @Suppress("UNCHECKED_CAST")
                val errors = it.details["errors"] as List<String>
                assertThat(errors)
                    .anyMatch { e ->
                        e.contains("webhooks:call")
                    }.anyMatch { e -> e.contains("type secret") }
            }
    }
}
