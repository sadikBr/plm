package com.plm.versioning

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeSettings
import com.plm.types.api.CreateSubtype
import com.plm.types.api.DataType
import com.plm.types.api.DefineAttribute
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.IterationStatus
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import plm.testing.MutableClock
import plm.testing.PlmIntegrationTest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@PlmIntegrationTest
class VersioningServiceIT(
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val directory: PrincipalDirectory,
    @Autowired private val types: TypeService,
    @Autowired private val attributeDefinitions: AttributeService,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))
    private val viewer = Actor(TenantId.DEFAULT, PrincipalId("victor-viewer"))
    private val outsider = Actor(TenantId.DEFAULT, PrincipalId("olga-outsider"))
    private val product: ContextId by lazy { createProduct() }

    private fun createProduct(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Org ${UUID.randomUUID()}"),
            )
        val product = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Drone")).id
        teams.put(admin, product, alice.principal, TeamRole.MEMBER)
        teams.put(admin, product, bob.principal, TeamRole.MEMBER)
        teams.put(admin, org.id, viewer.principal, TeamRole.VIEWER)
        return product
    }

    private fun newPart(attributes: Map<String, Any?> = mapOf("material" to "AL6061")) =
        versioning.create(alice, CreateVersionedObject(TypeId.PART, product, "Bracket", attributes = attributes))

    private fun assertCode(
        expectedType: Class<out PlmException>,
        code: String,
        call: ThrowingCallable,
    ) {
        assertThatThrownBy(call).isInstanceOfSatisfying(expectedType) { assertThat(it.code).isEqualTo(code) }
    }

    @Nested
    inner class Create {
        @Test
        fun `creates A_1 checked in, with an auto-assigned number`() {
            val obj = newPart()

            assertThat(obj.latest.version).isEqualTo("A.1")
            assertThat(obj.latest.status).isEqualTo(IterationStatus.CHECKED_IN)
            assertThat(obj.latest.attributes).containsEntry("material", "AL6061")
            assertThat(obj.number).matches("\\d{10}")
            assertThat(obj.checkedOutBy).isNull()
            assertThat(versioning.get(alice, obj.id)).isEqualTo(obj)
        }

        @Test
        fun `stores validated attributes with out-of-the-box defaults`() {
            val attrs =
                mapOf(
                    "mass" to mapOf("value" to 1.25, "unit" to "kg"),
                    "endItem" to true,
                    "description" to "Two\nlines",
                )

            val stored = newPart(attrs).latest.attributes

            assertThat(
                stored,
            ).containsAllEntriesOf(attrs).containsEntry("source", "make").containsEntry("traceCode", "untraced")
            assertThat(versioning.history(alice, newPart(attrs).id).single().attributes).isEqualTo(stored)
        }

        @Test
        fun `rejects invalid attributes with a reason per field`() {
            val error =
                catchThrowableOfType(ValidationException::class.java) {
                    newPart(
                        mapOf("source" to "steal", "mass" to mapOf("value" to 3, "unit" to "mm"), "colour" to "red"),
                    )
                }

            assertThat(error.code).isEqualTo("INVALID_ATTRIBUTES")
            @Suppress("UNCHECKED_CAST")
            val fields =
                (error.details["errors"] as List<Map<String, String>>).associate {
                    it["attribute"] to
                        it["code"]
                }
            assertThat(fields).isEqualTo(
                mapOf(
                    "colour" to "UNKNOWN_ATTRIBUTE",
                    "source" to "UNKNOWN_VALUE",
                    "mass" to "WRONG_DIMENSION",
                ),
            )
        }

        @Test
        fun `numbers are unique`() {
            val number = "P-" + UUID.randomUUID().toString().take(8)
            versioning.create(alice, CreateVersionedObject(TypeId.PART, product, "One", number = number))

            assertCode(ConflictException::class.java, "DUPLICATE_NUMBER") {
                versioning.create(alice, CreateVersionedObject(TypeId.PART, product, "Two", number = number))
            }
        }

        @Test
        fun `objects cannot live directly in an organization or the site`() {
            assertCode(ValidationException::class.java, "INVALID_CONTEXT") {
                versioning.create(admin, CreateVersionedObject(TypeId.PART, ContextId.DEFAULT_SITE, "Nope"))
            }
        }

        @Test
        fun `validates name, number, type and context`() {
            assertCode(ValidationException::class.java, "INVALID_NAME") {
                versioning.create(alice, CreateVersionedObject(TypeId.PART, product, "  "))
            }
            assertCode(ValidationException::class.java, "INVALID_NUMBER") {
                versioning.create(alice, CreateVersionedObject(TypeId.PART, product, "X", number = "has space"))
            }
            assertCode(NotFoundException::class.java, "TYPE_NOT_FOUND") {
                versioning.create(alice, CreateVersionedObject(TypeId(UUID.randomUUID()), product, "X"))
            }
            assertCode(NotFoundException::class.java, "CONTEXT_NOT_FOUND") {
                versioning.create(alice, CreateVersionedObject(TypeId.PART, ContextId(UUID.randomUUID()), "X"))
            }
        }
    }

    @Nested
    inner class CheckOutAndIn {
        @Test
        fun `check-out, edit, check-in produces the next iteration`() {
            val obj = newPart()

            val workingCopy = versioning.checkOut(alice, obj.id)
            assertThat(workingCopy.version).isEqualTo("A.2")
            assertThat(workingCopy.status).isEqualTo(IterationStatus.WORKING)
            assertThat(workingCopy.basedOnId).isEqualTo(obj.latest.id)
            assertThat(versioning.get(bob, obj.id).checkedOutBy).isEqualTo("alice")
            // Others keep seeing the latest checked-in iteration.
            assertThat(versioning.get(bob, obj.id).latest.version).isEqualTo("A.1")

            versioning.updateWorkingCopy(alice, obj.id, mapOf("material" to "Ti-6Al-4V"))
            clock.advance(Duration.ofMinutes(5))
            val checkedIn = versioning.checkIn(alice, obj.id, "  Switch to titanium  ")

            assertThat(checkedIn.latest.version).isEqualTo("A.2")
            assertThat(checkedIn.latest.status).isEqualTo(IterationStatus.CHECKED_IN)
            assertThat(checkedIn.latest.comment).isEqualTo("Switch to titanium")
            assertThat(checkedIn.latest.checkedInAt).isEqualTo(clock.instant())
            assertThat(checkedIn.checkedOutBy).isNull()
            assertThat(versioning.get(bob, obj.id)).isEqualTo(checkedIn)
            assertThat(versioning.history(alice, obj.id).map { it.version }).containsExactly("A.2", "A.1")
            assertThat(versioning.history(alice, obj.id).last().attributes).containsEntry("material", "AL6061")
        }

        @Test
        fun `undo check-out discards the working copy`() {
            val obj = newPart()
            versioning.checkOut(alice, obj.id)
            versioning.updateWorkingCopy(alice, obj.id, mapOf("material" to "scrap"))

            val after = versioning.undoCheckOut(alice, obj.id)

            assertThat(after.checkedOutBy).isNull()
            assertThat(after.latest).isEqualTo(obj.latest)
            assertThat(versioning.history(alice, obj.id)).hasSize(1)
            // The iteration number is free again.
            assertThat(versioning.checkOut(alice, obj.id).version).isEqualTo("A.2")
        }

        @Test
        fun `a second check-out is refused`() {
            val obj = newPart()
            versioning.checkOut(alice, obj.id)

            assertCode(ConflictException::class.java, "ALREADY_CHECKED_OUT") { versioning.checkOut(bob, obj.id) }
            assertCode(ConflictException::class.java, "ALREADY_CHECKED_OUT") { versioning.checkOut(alice, obj.id) }
        }

        @Test
        fun `only the owner can touch the working copy`() {
            val obj = newPart()
            versioning.checkOut(alice, obj.id)

            assertCode(
                ForbiddenException::class.java,
                "CHECKED_OUT_BY_ANOTHER_USER",
            ) { versioning.workingCopy(bob, obj.id) }
            assertCode(ForbiddenException::class.java, "CHECKED_OUT_BY_ANOTHER_USER") {
                versioning.updateWorkingCopy(bob, obj.id, emptyMap())
            }
            assertCode(
                ForbiddenException::class.java,
                "CHECKED_OUT_BY_ANOTHER_USER",
            ) { versioning.checkIn(bob, obj.id) }
            assertCode(
                ForbiddenException::class.java,
                "CHECKED_OUT_BY_ANOTHER_USER",
            ) { versioning.undoCheckOut(bob, obj.id) }
        }

        @Test
        fun `check-in, update and undo need a check-out`() {
            val obj = newPart()

            assertCode(ConflictException::class.java, "NOT_CHECKED_OUT") { versioning.checkIn(alice, obj.id) }
            assertCode(
                ConflictException::class.java,
                "NOT_CHECKED_OUT",
            ) { versioning.updateWorkingCopy(alice, obj.id, emptyMap()) }
            assertCode(ConflictException::class.java, "NOT_CHECKED_OUT") { versioning.undoCheckOut(alice, obj.id) }
        }

        @Test
        fun `concurrent check-outs - exactly one wins`() {
            val obj = newPart()
            val users = (1..8).map { Actor(TenantId.DEFAULT, PrincipalId("user$it-${UUID.randomUUID()}")) }
            users.forEach { teams.put(admin, product, it.principal, TeamRole.MEMBER) }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(users.size)

            val outcomes =
                users
                    .map { user ->
                        pool.submit<String> {
                            start.await()
                            try {
                                versioning.checkOut(user, obj.id)
                                "ok"
                            } catch (e: PlmException) {
                                e.code
                            }
                        }
                    }.also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()

            assertThat(outcomes.count { it == "ok" }).isEqualTo(1)
            assertThat(outcomes.filter { it != "ok" }).allMatch { it == "ALREADY_CHECKED_OUT" }
        }
    }

    @Nested
    inner class Revise {
        @Test
        fun `revise starts the next revision at iteration 1`() {
            val obj = newPart()
            versioning.checkOut(alice, obj.id)
            versioning.checkIn(alice, obj.id)

            val revised = versioning.revise(bob, obj.id)

            assertThat(revised.latest.version).isEqualTo("B.1")
            assertThat(revised.latest.createdBy).isEqualTo("bob")
            assertThat(revised.latest.attributes).isEqualTo(obj.latest.attributes)
            assertThat(versioning.history(alice, obj.id).map { it.version }).containsExactly("B.1", "A.2", "A.1")

            versioning.checkOut(alice, obj.id)
            assertThat(versioning.checkIn(alice, obj.id).latest.version).isEqualTo("B.2")
        }

        @Test
        fun `cannot revise while checked out`() {
            val obj = newPart()
            versioning.checkOut(alice, obj.id)

            assertCode(ConflictException::class.java, "CHECKED_OUT") { versioning.revise(alice, obj.id) }
        }
    }

    @Nested
    inner class AccessControl {
        @Test
        fun `viewers can read but not change`() {
            val obj = newPart()

            assertThat(versioning.get(viewer, obj.id).latest.version).isEqualTo("A.1")
            assertThat(versioning.history(viewer, obj.id)).hasSize(1)
            assertCode(ForbiddenException::class.java, "ACCESS_DENIED") { versioning.checkOut(viewer, obj.id) }
            assertCode(ForbiddenException::class.java, "ACCESS_DENIED") { versioning.revise(viewer, obj.id) }
            assertCode(ForbiddenException::class.java, "ACCESS_DENIED") {
                versioning.create(viewer, CreateVersionedObject(TypeId.PART, product, "Sneaky"))
            }
        }

        @Test
        fun `outsiders cannot tell the object exists`() {
            val obj = newPart()

            assertCode(NotFoundException::class.java, "OBJECT_NOT_FOUND") { versioning.get(outsider, obj.id) }
            assertCode(NotFoundException::class.java, "OBJECT_NOT_FOUND") { versioning.history(outsider, obj.id) }
            assertCode(NotFoundException::class.java, "OBJECT_NOT_FOUND") { versioning.checkOut(outsider, obj.id) }
            assertCode(
                NotFoundException::class.java,
                "CONTEXT_NOT_FOUND",
            ) { versioning.list(outsider, ObjectQuery(product)) }
        }

        @Test
        fun `list returns readable objects in pages`() {
            val created = (1..5).map { newPart(mapOf("material" to "M$it")) }

            val firstPage = versioning.list(viewer, ObjectQuery(product, limit = 2))
            val secondPage = versioning.list(viewer, ObjectQuery(product, limit = 2, after = firstPage.last().id))
            val all = versioning.list(alice, ObjectQuery(product))

            assertThat(all.map { it.id }).containsExactlyElementsOf(created.map { it.id })
            assertThat(firstPage.map { it.id }).containsExactlyElementsOf(created.take(2).map { it.id })
            assertThat(secondPage.map { it.id }).containsExactlyElementsOf(created.drop(2).take(2).map { it.id })
        }

        @Test
        fun `list shows who has an object checked out`() {
            val obj = newPart()
            versioning.checkOut(bob, obj.id)

            assertThat(
                versioning.list(viewer, ObjectQuery(product)).single { it.id == obj.id }.checkedOutBy,
            ).isEqualTo("bob")
        }

        @Test
        fun `search finds number or name across every readable context, and only those`() {
            val tag = UUID.randomUUID().toString().take(8)
            val mine = versioning.create(alice, CreateVersionedObject(TypeId.PART, product, "Bracket $tag-left"))
            val numbered =
                versioning.create(
                    alice,
                    CreateVersionedObject(TypeId.PART, product, "Other", number = "PN-$tag"),
                )
            // Same text, in a product alice is not on.
            val org =
                contexts.create(
                    admin,
                    CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Hidden ${UUID.randomUUID()}"),
                )
            val hidden = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Secret"))
            versioning.create(admin, CreateVersionedObject(TypeId.PART, hidden.id, "Bracket $tag-secret"))

            val found = versioning.list(alice, ObjectQuery(text = tag.uppercase())).map { it.id }

            assertThat(found).containsExactlyInAnyOrder(mine.id, numbered.id)
            assertThat(versioning.list(admin, ObjectQuery(text = tag))).hasSize(3)
        }

        @Test
        fun `search treats wildcard characters literally`() {
            val tag = UUID.randomUUID().toString().take(8)
            val literal = versioning.create(alice, CreateVersionedObject(TypeId.PART, product, "100% cotton_$tag"))
            versioning.create(alice, CreateVersionedObject(TypeId.PART, product, "100x cottonX$tag"))

            assertThat(
                versioning.list(alice, ObjectQuery(text = "100% cotton_$tag")).map { it.id },
            ).containsExactly(literal.id)
        }

        @Test
        fun `conflict messages use display names`() {
            directory.recordSignIn(SignIn(TenantId.DEFAULT, alice.principal, "alice", "Alice Admin", null))
            val obj = newPart()
            versioning.checkOut(alice, obj.id)

            assertThatThrownBy { versioning.checkOut(bob, obj.id) }.hasMessageContaining("checked out by Alice Admin")
        }
    }

    @Nested
    inner class Attributes {
        @Test
        fun `working copy edits are validated and normalized`() {
            val obj = newPart()
            versioning.checkOut(alice, obj.id)

            assertThatThrownBy { versioning.updateWorkingCopy(alice, obj.id, mapOf("phantom" to "maybe")) }
                .isInstanceOfSatisfying(
                    ValidationException::class.java,
                ) { assertThat(it.code).isEqualTo("INVALID_ATTRIBUTES") }

            val updated =
                versioning.updateWorkingCopy(
                    alice,
                    obj.id,
                    mapOf(
                        "mass" to mapOf("value" to 250, "unit" to "g"),
                    ),
                )
            assertThat(updated.attributes["mass"]).isEqualTo(mapOf("value" to 250.0, "unit" to "g"))
            assertThat(updated.attributes["source"]).isEqualTo("make")
        }

        @Test
        fun `check-in enforces attributes that became required after check-out`() {
            val type =
                types.createSubtype(
                    admin,
                    CreateSubtype(
                        TypeId.PART,
                        "Valve" +
                            UUID
                                .randomUUID()
                                .toString()
                                .take(8)
                                .replace("-", ""),
                        "Valve",
                    ),
                )
            val pressure =
                attributeDefinitions.define(
                    admin,
                    DefineAttribute(
                        type.id,
                        "ratedPressure",
                        DataType.QUANTITY,
                        dimension = "PRESSURE",
                        settings =
                            AttributeSettings(
                                mapOf(
                                    "en" to "Rated pressure",
                                ),
                            ),
                    ),
                )
            val obj = versioning.create(alice, CreateVersionedObject(type.id, product, "Ball valve"))
            versioning.checkOut(alice, obj.id)
            attributeDefinitions.update(
                admin,
                pressure.id,
                AttributeSettings(mapOf("en" to "Rated pressure"), required = true),
            )

            assertThatThrownBy { versioning.checkIn(alice, obj.id) }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.message).contains("Rated pressure is required")
                }

            versioning.updateWorkingCopy(alice, obj.id, mapOf("ratedPressure" to mapOf("value" to 16, "unit" to "bar")))
            assertThat(versioning.checkIn(alice, obj.id).latest.attributes).containsKey("ratedPressure")
        }
    }

    @Nested
    inner class Events {
        private fun outbox(id: MasterId) =
            jdbc
                .sql(
                    "select event_type, payload::text as payload from outbox_event where aggregate_id = :id order by seq",
                ).param("id", id.value)
                .query { rs, _ -> rs.getString("event_type") to rs.getString("payload") }
                .list()

        @Test
        fun `every change publishes an event, in order`() {
            val obj = newPart()
            versioning.checkOut(alice, obj.id)
            versioning.updateWorkingCopy(alice, obj.id, mapOf("material" to "Ti"))
            versioning.checkIn(alice, obj.id, "done")
            versioning.revise(alice, obj.id)
            versioning.checkOut(alice, obj.id)
            versioning.undoCheckOut(alice, obj.id)

            val events = outbox(obj.id)
            assertThat(events.map { it.first }).containsExactly(
                "versioning.ObjectCreated.v1",
                "versioning.ObjectCheckedOut.v1",
                "versioning.WorkingCopyUpdated.v1",
                "versioning.ObjectCheckedIn.v1",
                "versioning.ObjectRevised.v1",
                "versioning.ObjectCheckedOut.v1",
                "versioning.CheckOutUndone.v1",
            )
            val updated = events[2].second
            assertThat(updated).contains("\"material\": \"AL6061\"", "\"material\": \"Ti\"")
            assertThat(events.map { it.second }).noneMatch { it.contains("aggregateType") || it.contains("eventType") }
        }

        @Test
        fun `failed operations publish nothing`() {
            val obj = newPart()
            versioning.checkOut(alice, obj.id)
            runCatching { versioning.checkOut(bob, obj.id) }
            runCatching { versioning.checkIn(bob, obj.id) }

            assertThat(outbox(obj.id).map { it.first }).containsExactly(
                "versioning.ObjectCreated.v1",
                "versioning.ObjectCheckedOut.v1",
            )
        }
    }

    @Nested
    inner class HistoryIsImmutable {
        @Test
        fun `the database refuses to modify a checked-in iteration`() {
            val obj = newPart()

            assertThatThrownBy {
                jdbc
                    .sql("update object_iteration set attrs = '{\"hacked\":true}' where id = :id")
                    .param("id", obj.latest.id.value)
                    .update()
            }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("cannot be modified")
        }

        @Test
        fun `the database refuses to delete a checked-in iteration`() {
            val obj = newPart()

            assertThatThrownBy {
                jdbc.sql("delete from object_iteration where id = :id").param("id", obj.latest.id.value).update()
            }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("cannot be deleted")
        }

        @Test
        fun `lifecycle state is the one column that may still change`() {
            val obj = newPart()

            jdbc
                .sql(
                    "update object_iteration set state = 'RELEASED' where id = :id",
                ).param("id", obj.latest.id.value)
                .update()

            assertThat(versioning.get(alice, obj.id).latest.state).isEqualTo("RELEASED")
        }
    }

    @Test
    fun `objects are invisible to other tenants`() {
        val obj = newPart()
        val otherTenant = TenantId(UUID.randomUUID())
        jdbc
            .sql(
                "insert into tenant (id, name) values (:id, :name)",
            ).param("id", otherTenant.value)
            .param("name", "t-$otherTenant")
            .update()
        val mallory = Actor(otherTenant, PrincipalId("mallory"))

        assertCode(NotFoundException::class.java, "OBJECT_NOT_FOUND") { versioning.get(mallory, obj.id) }
        assertCode(NotFoundException::class.java, "OBJECT_NOT_FOUND") { versioning.checkOut(mallory, obj.id) }
    }

    @Test
    fun `unknown objects are reported as not found`() {
        assertCode(
            NotFoundException::class.java,
            "OBJECT_NOT_FOUND",
        ) { versioning.get(alice, MasterId(UUID.randomUUID())) }
    }
}
