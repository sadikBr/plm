package com.plm.extensions

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.extensions.api.EventSubscriptionService
import com.plm.extensions.api.EventSubscriptionSettings
import com.plm.extensions.api.ExtensionService
import com.plm.extensions.domain.EventWebhooksTestHook
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import plm.testing.MutableClock
import plm.testing.PlmIntegrationTest
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Roadmap 2.6: events delivered to subscribers, signed, retried with backoff, dead-lettered. */
@PlmIntegrationTest
@TestPropertySource(properties = ["plm.webhooks.sender.enabled=false", "plm.webhooks.backoff=10s"])
class EventWebhooksIT(
    @Autowired private val subscriptions: EventSubscriptionService,
    @Autowired private val sender: EventWebhooksTestHook,
    @Autowired private val extensions: ExtensionService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val types: TypeService,
    @Autowired private val clock: MutableClock,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val json = JsonMapper.builder().build()
    private val received = CopyOnWriteArrayList<Pair<Map<String, String>, String>>()

    /** The receiver answers this status (500: failing). */
    private val answer = AtomicInteger(200)
    private lateinit var receiver: HttpServer
    private val created = mutableListOf<UUID>()

    @BeforeEach
    fun start() {
        receiver = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        receiver.createContext("/") { ex ->
            received +=
                ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() } to
                ex.requestBody.readAllBytes().decodeToString()
            ex.sendResponseHeaders(answer.get(), -1)
            ex.close()
        }
        receiver.start()
        sender.deliverDue() // leftovers from other tests aren't ours
        received.clear()
    }

    @AfterEach
    fun stop() {
        receiver.stop(0)
        created.forEach { subscriptions.delete(admin, it) }
    }

    private fun url() = "http://127.0.0.1:${receiver.address.port}/hooks"

    /** A part type of its own, so filters can pick this test's events out of everything. */
    private val typeName = "Hooked${UUID.randomUUID().toString().take(8)}"
    private val type by lazy { types.createSubtype(admin, CreateSubtype(TypeId.PART, typeName, "Hooked")).id }

    private fun subscribe(
        events: List<String>,
        filter: String? = "event.typeId == '${type.value}'",
        maxAttempts: Int = 8,
    ) = subscriptions
        .define(
            admin,
            EventSubscriptionSettings("Hook ${UUID.randomUUID()}", events, filter, url(), "s3cret", maxAttempts),
        ).also { created += UUID.fromString(it.id) }

    private fun part(): com.plm.versioning.api.VersionedObject {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Hooks ${UUID.randomUUID()}"),
            )
        val product = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Arm")).id
        teams.put(admin, product, alice.principal, TeamRole.MEMBER)
        return versioning.create(alice, CreateVersionedObject(type, product, "Elbow"))
    }

    @Test
    fun `matching events are delivered signed, with an id to deduplicate on`() {
        subscribe(listOf("versioning.ObjectCreated.v1"))
        val obj = part()
        sender.deliverDue()

        val (headers, body) = received.single()
        assertThat(headers["x-plm-event"]).isEqualTo("versioning.ObjectCreated.v1")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("s3cret".toByteArray(), "HmacSHA256")) }
        val expected =
            "sha256=" + HexFormat.of().formatHex(mac.doFinal("${headers["x-plm-timestamp"]}.$body".toByteArray()))
        assertThat(headers["x-plm-signature"]).isEqualTo(expected)
        val sent = json.readValue(body, Map::class.java)
        assertThat(sent["deliveryId"]).isEqualTo(headers["x-plm-delivery"])
        assertThat(((sent["event"] as Map<*, *>)["payload"] as Map<*, *>)["number"]).isEqualTo(obj.number)
        assertThat(
            subscriptions.deliveries(admin, sent["subscription"] as String).single().status,
        ).isEqualTo("DELIVERED")
    }

    @Test
    fun `wildcards and filters choose the events`() {
        subscribe(
            listOf("versioning.*"),
            "event.typeId == '${type.value}' && event.type == 'versioning.ObjectStateChanged.v1' && event.to == 'RELEASED'",
        )
        val obj = part()
        versioning.setState(admin, obj.id, SetState("UNDER_REVIEW", override = true))
        versioning.setState(admin, obj.id, SetState("RELEASED", override = true))
        sender.deliverDue()
        assertThat(
            received
                .map {
                    json.readValue(it.second, Map::class.java)["event"] as Map<*, *>
                }.map { (it["payload"] as Map<*, *>)["to"] },
        ).containsExactly("RELEASED")
    }

    @Test
    fun `failures are retried with growing waits, then dead-lettered, and can be redelivered`() {
        val s = subscribe(listOf("versioning.ObjectCreated.v1"), maxAttempts = 3)
        answer.set(500)
        part()
        sender.deliverDue()
        var d = subscriptions.deliveries(admin, s.id).single()
        assertThat(d.status).isEqualTo("PENDING")
        assertThat(d.attempts).isEqualTo(1)
        assertThat(d.lastHttpStatus).isEqualTo(500)
        assertThat(Duration.between(clock.instant(), d.nextAttemptAt)).isEqualTo(Duration.ofSeconds(10))

        sender.deliverDue() // not due yet
        assertThat(subscriptions.deliveries(admin, s.id).single().attempts).isEqualTo(1)

        clock.advance(Duration.ofSeconds(10))
        sender.deliverDue()
        d = subscriptions.deliveries(admin, s.id).single()
        assertThat(Duration.between(clock.instant(), d.nextAttemptAt)).isEqualTo(Duration.ofSeconds(20)) // doubled

        clock.advance(Duration.ofSeconds(20))
        sender.deliverDue()
        d = subscriptions.deliveries(admin, s.id).single()
        assertThat(d.status).isEqualTo("DEAD")
        assertThat(d.attempts).isEqualTo(3)
        assertThat(subscriptions.health(admin).single { it.subscriptionId == s.id }.dead).isEqualTo(1)

        answer.set(204)
        subscriptions.redeliver(admin, d.id)
        sender.deliverDue()
        assertThat(subscriptions.deliveries(admin, s.id).single().status).isEqualTo("DELIVERED")
        assertThat(received).hasSize(4)
    }

    @Test
    fun `an unreachable receiver is a failure like any other`() {
        val s = subscribe(listOf("versioning.ObjectCreated.v1"))
        subscriptions.update(
            admin,
            UUID.fromString(s.id),
            EventSubscriptionSettings(s.name, s.events, s.filter, "http://127.0.0.1:1/none"),
        )
        part()
        sender.deliverDue()
        assertThat(subscriptions.deliveries(admin, s.id).single().lastError).isNotBlank()
    }

    @Test
    fun `an extension's subscription delivers to its service with its secret`() {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "extensions/samples").isDirectory) dir = dir.parentFile
        val id = "acme.erp-${UUID.randomUUID().toString().take(8)}"
        val manifest =
            File(dir, "extensions/samples/erp-sync/plm-extension-2.0.yaml")
                .readText()
                .replace("id: acme.erp-sync", "id: $id")
                .replace("version: 2.0.0", "version: 2.1.0")
                .replace("  - webhooks:call\n", "  - webhooks:call\n  - events:subscribe\n") +
                """
                |subscriptions:
                |  - id: released
                |    name: Released parts
                |    events: [versioning.ObjectStateChanged.v1]
                |    filter: "event.to == 'RELEASED' && event.typeId == '${type.value}'"
                |    deliver:
                |      webhook:
                |        url: "${'$'}{config.erpApiUrl}/events"
                |        secret: apiKey
                |        retries: 5
                |
                """.trimMargin()
        extensions.install(
            admin,
            manifest,
            mapOf(
                "erpUrl" to "https://erp.example",
                "erpApiUrl" to "http://127.0.0.1:${receiver.address.port}",
                "apiKey" to "k3y",
            ),
        )
        try {
            val obj = part()
            versioning.setState(admin, obj.id, SetState("RELEASED", override = true))
            sender.deliverDue()
            val (headers, body) = received.single()
            assertThat(headers["x-plm-event"]).isEqualTo("versioning.ObjectStateChanged.v1")
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("k3y".toByteArray(), "HmacSHA256")) }
            assertThat(headers["x-plm-signature"]).isEqualTo(
                "sha256=" + HexFormat.of().formatHex(mac.doFinal("${headers["x-plm-timestamp"]}.$body".toByteArray())),
            )
            assertThat(
                subscriptions
                    .list(admin)
                    .single {
                        it.id == "$id.released"
                    }.url,
            ).isEqualTo("http://127.0.0.1:${receiver.address.port}/events")
        } finally {
            extensions.uninstall(admin, id)
        }
    }

    @Test
    fun `subscriptions are checked and for site administrators`() {
        assertThatThrownBy {
            subscriptions.define(admin, EventSubscriptionSettings("X", listOf("not an event"), null, url(), "s"))
        }.isInstanceOfSatisfying(
            ValidationException::class.java,
        ) { assertThat(it.code).isEqualTo("INVALID_SUBSCRIPTION") }
        assertThatThrownBy {
            subscriptions.define(admin, EventSubscriptionSettings("X", listOf("*"), "event.to ==", url(), "s"))
        }.isInstanceOfSatisfying(
            ValidationException::class.java,
        ) { assertThat(it.code).isEqualTo("INVALID_EXPRESSION") }
        assertThatThrownBy {
            subscriptions.define(
                admin,
                EventSubscriptionSettings("X", listOf("*"), null, url(), null),
            )
        }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { subscriptions.list(alice) }.isInstanceOf(ForbiddenException::class.java)
    }
}
