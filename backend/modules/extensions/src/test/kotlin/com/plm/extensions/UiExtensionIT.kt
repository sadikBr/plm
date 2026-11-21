package com.plm.extensions

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.extensions.api.ExtensionService
import com.plm.extensions.domain.UiExtensions
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** UI extension slots and extension endpoints (roadmap 5.15). */
@PlmIntegrationTest
internal class UiExtensionIT(
    @Autowired private val extensions: ExtensionService,
    @Autowired private val ui: UiExtensions,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val id = "test.ui-${UUID.randomUUID().toString().take(6)}"
    private val seen = mutableListOf<Map<String, String?>>()
    private val service =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                val ts = ex.requestHeaders.getFirst("X-PLM-Timestamp")
                val mac =
                    Mac
                        .getInstance(
                            "HmacSHA256",
                        ).apply { init(SecretKeySpec("s3cret".toByteArray(), "HmacSHA256")) }
                val expected =
                    "sha256=" +
                        java.util.HexFormat
                            .of()
                            .formatHex(mac.doFinal("$ts.$body".toByteArray()))
                seen +=
                    mapOf(
                        "path" to ex.requestURI.toString(),
                        "method" to ex.requestMethod,
                        "user" to ex.requestHeaders.getFirst("X-PLM-User"),
                        "signed" to (ex.requestHeaders.getFirst("X-PLM-Signature") == expected).toString(),
                        "body" to body,
                    )
                val answer = """{"inStock": 42}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, answer.size.toLong())
                ex.responseBody.use { it.write(answer) }
            }
            start()
        }
    private val base = "http://127.0.0.1:${service.address.port}"

    @AfterEach
    fun stop() {
        service.stop(0)
        runCatching { extensions.uninstall(admin, id) }
    }

    private fun manifest(extra: String = "") =
        """
        id: $id
        name: Stock
        version: 1.0.0
        platform: ">=1.0 <2.0"
        permissions: [ui:panels, endpoints:serve]
        config:
          service: { type: url, label: Service, required: true }
          key: { type: secret, label: Key, required: true }
        ui:
          - id: stock
            slot: object.panel
            title: Stock
            url: "${'$'}{config.service}/ui/stock?n=${'$'}{object.number}"
            appliesTo: { types: [Part] }
            height: 180
          - id: docs
            slot: object.tab
            title: Docs only
            url: "${'$'}{config.service}/ui/docs"
            appliesTo: { types: [Document] }
        endpoints:
          - id: stock
            path: stock
            url: "${'$'}{config.service}/api/stock"
            secret: key
            methods: [GET, POST]
        $extra
        """.trimIndent()

    @Test
    fun `extensions add panels to the objects they apply to and serve endpoints, signed`() {
        extensions.install(admin, manifest(), mapOf("service" to base, "key" to "s3cret"))
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "UI ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Arm")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Bracket"))

        val slots = ui.slots(alice, part.id.value).filter { it.extensionId == id }
        assertThat(slots.map { it.id }).containsExactly("stock")
        assertThat(slots.single().url).isEqualTo("$base/ui/stock?n=${part.number}")
        assertThat(slots.single().origin).isEqualTo(base)

        val r =
            ui.forward(
                alice,
                id,
                "POST",
                "stock/${part.number}",
                "plant=P100",
                "application/json",
                """{"q":1}""".toByteArray(),
            )
        assertThat(r.status).isEqualTo(200)
        assertThat(String(r.body)).contains("42")
        assertThat(seen.single())
            .containsEntry("path", "/api/stock/${part.number}?plant=P100")
            .containsEntry("user", "alice")
            .containsEntry("signed", "true")
            .containsEntry("body", """{"q":1}""")
        assertThat(ui.forward(alice, id, "DELETE", "stock", null, null, ByteArray(0)).status).isEqualTo(405)
        assertThatThrownBy {
            ui.forward(
                alice,
                id,
                "GET",
                "other",
                null,
                null,
                ByteArray(0),
            )
        }.hasMessageContaining("No such")
        assertThatThrownBy {
            ui.forward(alice, id, "GET", "stock/../../admin", null, null, ByteArray(0))
        }.hasMessageContaining("No such")
    }

    @Test
    fun `slots and endpoints need their permissions`() {
        assertThatThrownBy {
            extensions.install(
                admin,
                manifest().replace("permissions: [ui:panels, endpoints:serve]", "permissions: [ui:panels]"),
            )
        }.hasMessageContaining("endpoints:serve")
    }
}
