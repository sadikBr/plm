package com.plm.versioning

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.lifecycle.api.CreateLifecycleTemplate
import com.plm.lifecycle.api.LifecycleService
import com.plm.lifecycle.api.LifecycleState
import com.plm.lifecycle.api.LifecycleTemplate
import com.plm.lifecycle.api.LifecycleTemplateId
import com.plm.lifecycle.api.LifecycleTransition
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.preferences.api.PreferenceService
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeSettings
import com.plm.types.api.CreateNumberingScheme
import com.plm.types.api.CreateSubtype
import com.plm.types.api.DataType
import com.plm.types.api.DefineAttribute
import com.plm.types.api.RevisionScheme
import com.plm.types.api.TypeId
import com.plm.types.api.TypeProperties
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeRuleSettings
import com.plm.types.api.TypeService
import com.plm.types.api.ValidationRuleService
import com.plm.types.api.ValidationRuleSettings
import com.plm.versioning.api.ChangeLifecycle
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.IterationStatus
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.Rename
import com.plm.versioning.api.SetState
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
    @Autowired private val typeRules: TypeRuleService,
    @Autowired private val lifecycles: LifecycleService,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
    @Autowired private val preferences: PreferenceService,
    @Autowired private val validationRules: ValidationRuleService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))
    private val viewer = Actor(TenantId.DEFAULT, PrincipalId("victor-viewer"))
    private val outsider = Actor(TenantId.DEFAULT, PrincipalId("olga-outsider"))

    /** Product manager: may promote (change lifecycle state). */
    private val manager = Actor(TenantId.DEFAULT, PrincipalId("mona-manager"))
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
        teams.put(admin, product, manager.principal, TeamRole.MANAGER)
        teams.put(admin, org.id, viewer.principal, TeamRole.VIEWER)
        return product
    }

    /** In Work -> Under Review -> Released, the way a manager would. */
    private fun release(id: MasterId) {
        versioning.setState(manager, id, SetState("UNDER_REVIEW"))
        versioning.setState(manager, id, SetState("RELEASED", "Approved"))
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
            release(obj.id)

            val revised = versioning.revise(bob, obj.id)

            assertThat(revised.latest.version).isEqualTo("B.1")
            assertThat(revised.latest.state).isEqualTo("IN_WORK")
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
    inner class Lifecycle {
        @Test
        fun `new objects start in the lifecycle's initial state, editable but not revisable`() {
            val obj = newPart()
            val info = versioning.lifecycle(alice, obj.id)

            assertThat(obj.latest.state).isEqualTo("IN_WORK")
            assertThat(info.template.name).isEqualTo("Basic")
            assertThat(info.editable).isTrue()
            assertThat(info.revisable).isFalse()
            assertThat(info.nextStates).describedAs("members can't promote").isEmpty()
            assertThat(versioning.lifecycle(manager, obj.id).nextStates).containsExactly("UNDER_REVIEW", "CANCELED")
        }

        @Test
        fun `revise is only allowed from Released, and starts In Work again`() {
            val obj = newPart()
            assertCode(ConflictException::class.java, "REVISE_NOT_ALLOWED") { versioning.revise(alice, obj.id) }

            release(obj.id)
            val revised = versioning.revise(alice, obj.id)

            assertThat(revised.latest.version).isEqualTo("B.1")
            assertThat(revised.latest.state).isEqualTo("IN_WORK")
            // The released iteration keeps its state in history.
            assertThat(versioning.history(alice, obj.id).map { it.version to it.state })
                .containsExactly("B.1" to "IN_WORK", "A.1" to "RELEASED")
        }

        @Test
        fun `released objects are read-only until revised`() {
            val obj = newPart()
            release(obj.id)

            assertThatThrownBy { versioning.checkOut(alice, obj.id) }
                .isInstanceOfSatisfying(ConflictException::class.java) {
                    assertThat(it.code).isEqualTo("NOT_EDITABLE")
                    assertThat(it.message).contains("is Released", "Revise it")
                }
            assertThat(versioning.lifecycle(alice, obj.id).editable).isFalse()
        }

        @Test
        fun `state changes follow the lifecycle's transitions and need PROMOTE`() {
            val obj = newPart()

            assertCode(ForbiddenException::class.java, "ACCESS_DENIED") {
                versioning.setState(alice, obj.id, SetState("UNDER_REVIEW"))
            }
            assertCode(ConflictException::class.java, "TRANSITION_NOT_ALLOWED") {
                versioning.setState(manager, obj.id, SetState("RELEASED"))
            }
            assertCode(ValidationException::class.java, "UNKNOWN_STATE") {
                versioning.setState(manager, obj.id, SetState("SHIPPED"))
            }
            assertCode(
                ValidationException::class.java,
                "SAME_STATE",
            ) { versioning.setState(manager, obj.id, SetState("IN_WORK")) }

            versioning.checkOut(alice, obj.id)
            assertCode(ConflictException::class.java, "CHECKED_OUT") {
                versioning.setState(manager, obj.id, SetState("UNDER_REVIEW"))
            }
            versioning.undoCheckOut(alice, obj.id)

            assertThat(
                versioning.setState(manager, obj.id, SetState("UNDER_REVIEW")).latest.state,
            ).isEqualTo("UNDER_REVIEW")
            assertThat(versioning.lifecycle(manager, obj.id).nextStates).containsExactly("IN_WORK", "RELEASED")
        }

        @Test
        fun `site administrators can override the lifecycle, nobody else`() {
            val obj = newPart()

            assertCode(ForbiddenException::class.java, "ADMIN_REQUIRED") {
                versioning.setState(manager, obj.id, SetState("RELEASED", override = true))
            }
            assertThat(
                versioning.setState(admin, obj.id, SetState("RELEASED", "Legacy import", override = true)).latest.state,
            ).isEqualTo("RELEASED")
        }

        @Test
        fun `a numbering scheme on a type numbers new objects, and can forbid manual numbers`() {
            val type =
                types.createSubtype(
                    admin,
                    CreateSubtype(
                        TypeId.PART,
                        "Gear" +
                            UUID
                                .randomUUID()
                                .toString()
                                .take(6)
                                .replace("-", ""),
                        "Gear",
                    ),
                )
            val scheme =
                typeRules.createNumberingScheme(
                    admin,
                    CreateNumberingScheme("Gears ${UUID.randomUUID()}", prefix = "GR-", padding = 4, start = 7),
                )
            typeRules.updateRules(
                admin,
                type.id,
                TypeRuleSettings(numberingSchemeId = scheme.id, allowManualNumber = false),
            )

            val first = versioning.create(alice, CreateVersionedObject(type.id, product, "Spur gear"))
            val second = versioning.create(alice, CreateVersionedObject(type.id, product, "Bevel gear"))

            assertThat(listOf(first.number, second.number)).containsExactly("GR-0007", "GR-0008")
            assertCode(ValidationException::class.java, "MANUAL_NUMBER_NOT_ALLOWED") {
                versioning.create(alice, CreateVersionedObject(type.id, product, "Rack", number = "MY-1"))
            }
        }

        @Test
        fun `concurrent creates never get the same number`() {
            val type =
                types.createSubtype(
                    admin,
                    CreateSubtype(
                        TypeId.PART,
                        "Pin" +
                            UUID
                                .randomUUID()
                                .toString()
                                .take(6)
                                .replace("-", ""),
                        "Pin",
                    ),
                )
            val scheme =
                typeRules.createNumberingScheme(
                    admin,
                    CreateNumberingScheme("Pins ${UUID.randomUUID()}", prefix = "PIN-"),
                )
            typeRules.updateRules(admin, type.id, TypeRuleSettings(numberingSchemeId = scheme.id))
            val pool = Executors.newFixedThreadPool(8)

            val numbers =
                (1..24)
                    .map {
                        pool.submit<String> {
                            versioning
                                .create(
                                    alice,
                                    CreateVersionedObject(type.id, product, "Pin $it"),
                                ).number
                        }
                    }.map { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()

            assertThat(numbers).doesNotHaveDuplicates().hasSize(24).allMatch { it.matches(Regex("PIN-0000\\d\\d")) }
        }

        @Test
        fun `objects keep the revision scheme and lifecycle they were created with`() {
            val type =
                types.createSubtype(
                    admin,
                    CreateSubtype(
                        TypeId.PART,
                        "Proto" +
                            UUID
                                .randomUUID()
                                .toString()
                                .take(6)
                                .replace("-", ""),
                        "Prototype",
                    ),
                )
            typeRules.updateRules(admin, type.id, TypeRuleSettings(revisionScheme = RevisionScheme.NUMERIC_FROM_0))
            val simple =
                lifecycles.create(
                    admin,
                    CreateLifecycleTemplate(
                        "Simple ${UUID.randomUUID()}",
                        mapOf("en" to "Simple"),
                        "DRAFT",
                        listOf(
                            LifecycleState("DRAFT", mapOf("en" to "Draft"), editable = true, revisable = false),
                            LifecycleState("APPROVED", mapOf("en" to "Approved"), editable = false, revisable = true),
                        ),
                        listOf(LifecycleTransition("DRAFT", "APPROVED")),
                    ),
                )
            lifecycles.assign(admin, type.id, simple.id)
            val obj = versioning.create(alice, CreateVersionedObject(type.id, product, "Prototype bracket"))
            assertThat(obj.latest.version).isEqualTo("0.1")
            assertThat(obj.latest.state).isEqualTo("DRAFT")

            // The type changes its rules afterwards...
            typeRules.updateRules(admin, type.id, TypeRuleSettings(revisionScheme = RevisionScheme.ALPHABETIC))
            lifecycles.assign(admin, type.id, LifecycleTemplateId.BASIC)

            // ...but this object still follows its own.
            versioning.setState(manager, obj.id, SetState("APPROVED"))
            val revised = versioning.revise(alice, obj.id)
            assertThat(revised.latest.version).isEqualTo("1.1")
            assertThat(revised.latest.state).isEqualTo("DRAFT")
            assertThat(
                versioning.create(alice, CreateVersionedObject(type.id, product, "Next")).latest.version,
            ).isEqualTo("A.1")
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
            release(obj.id)
            versioning.revise(alice, obj.id)
            versioning.checkOut(alice, obj.id)
            versioning.undoCheckOut(alice, obj.id)

            val events = outbox(obj.id)
            assertThat(events.map { it.first }).containsExactly(
                "versioning.ObjectCreated.v1",
                "versioning.ObjectCheckedOut.v1",
                "versioning.WorkingCopyUpdated.v1",
                "versioning.ObjectCheckedIn.v1",
                "versioning.ObjectStateChanged.v1",
                "versioning.ObjectStateChanged.v1",
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

    @Nested
    inner class LifecycleRules {
        /** Prototype numbers while in work, letters once approved; approving needs a material. */
        private fun setup(): Pair<TypeId, LifecycleTemplate> {
            val type =
                types
                    .createSubtype(
                        admin,
                        CreateSubtype(
                            TypeId.PART,
                            "Lc" +
                                UUID
                                    .randomUUID()
                                    .toString()
                                    .take(8)
                                    .replace("-", ""),
                            "Lc",
                        ),
                    ).id
            typeRules.updateRules(admin, type, TypeRuleSettings(revisionScheme = RevisionScheme.NUMERIC_FROM_1))
            val lc =
                lifecycles.create(
                    admin,
                    CreateLifecycleTemplate(
                        "Gated ${UUID.randomUUID()}",
                        mapOf("en" to "Gated"),
                        "DRAFT",
                        listOf(
                            LifecycleState("DRAFT", mapOf("en" to "Draft"), editable = true, revisable = true),
                            LifecycleState(
                                "APPROVED",
                                mapOf("en" to "Approved"),
                                editable = false,
                                revisable = true,
                                requiredAttributes = listOf("material"),
                                revisionScheme = RevisionScheme.ALPHABETIC_ASME,
                            ),
                        ),
                        listOf(LifecycleTransition("DRAFT", "APPROVED")),
                    ),
                )
            lifecycles.assign(admin, type, lc.id)
            return type to lc
        }

        @Test
        fun `a state's entry criteria must be met, unless an admin overrides`() {
            val (type) = setup()
            val obj = versioning.create(alice, CreateVersionedObject(type, product, "Gated part"))

            assertThatThrownBy { versioning.setState(manager, obj.id, SetState("APPROVED")) }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo("ENTRY_CRITERIA_NOT_MET")
                    assertThat(it.details["missing"]).isEqualTo(listOf("material"))
                }
            versioning.checkOut(bob, obj.id)
            versioning.updateWorkingCopy(bob, obj.id, mapOf("material" to "Ti"))
            versioning.checkIn(bob, obj.id)
            assertThat(versioning.setState(manager, obj.id, SetState("APPROVED")).latest.state).isEqualTo("APPROVED")

            val other = versioning.create(alice, CreateVersionedObject(type, product, "Other"))
            assertThat(
                versioning.setState(admin, other.id, SetState("APPROVED", override = true)).latest.state,
            ).isEqualTo("APPROVED")
        }

        @Test
        fun `revisions switch series when the state says so`() {
            val (type) = setup()
            val obj =
                versioning.create(
                    alice,
                    CreateVersionedObject(
                        type,
                        product,
                        "Series",
                        attributes =
                            mapOf(
                                "material" to "Al",
                            ),
                    ),
                )
            assertThat(obj.latest.version).isEqualTo("1.1")

            assertThat(versioning.revise(alice, obj.id).latest.version).isEqualTo("2.1") // from Draft: same series
            versioning.setState(manager, obj.id, SetState("APPROVED"))
            assertThat(versioning.revise(alice, obj.id).latest.version).isEqualTo("A.1") // from Approved: letters start
            versioning.setState(manager, obj.id, SetState("APPROVED"))
            assertThat(versioning.revise(alice, obj.id).latest.version).isEqualTo("B.1")
            assertThat(versioning.revise(alice, obj.id).latest.version).isEqualTo("C.1") // stays in letters
        }

        @Test
        fun `objects keep their lifecycle version until an admin moves them`() {
            val (type, v1) = setup()
            val old = versioning.create(alice, CreateVersionedObject(type, product, "Old"))
            val v2 =
                lifecycles.update(
                    admin,
                    v1.id,
                    CreateLifecycleTemplate(
                        v1.name,
                        v1.labels,
                        "WIP",
                        listOf(
                            LifecycleState("WIP", mapOf("en" to "Work"), editable = true, revisable = false),
                            LifecycleState(
                                "DONE",
                                mapOf(
                                    "en" to "Done",
                                ),
                                false,
                                true,
                            ),
                        ),
                        listOf(LifecycleTransition("WIP", "DONE")),
                    ),
                )

            assertThat(versioning.lifecycle(alice, old.id).template.id).isEqualTo(v1.id)
            assertThat(
                versioning.create(alice, CreateVersionedObject(type, product, "New")).latest.state,
            ).isEqualTo("WIP")
            assertCode(ValidationException::class.java, "STATE_MAPPING_REQUIRED") {
                versioning.changeLifecycle(admin, old.id, ChangeLifecycle(v2.id))
            }
            assertCode(ForbiddenException::class.java, "ADMIN_REQUIRED") {
                versioning.changeLifecycle(manager, old.id, ChangeLifecycle(v2.id, "WIP"))
            }
            val moved = versioning.changeLifecycle(admin, old.id, ChangeLifecycle(v2.id, "WIP"))
            assertThat(moved.latest.state).isEqualTo("WIP")
            assertThat(versioning.lifecycle(alice, old.id).template.id).isEqualTo(v2.id)
        }
    }

    @Nested
    inner class Preferences {
        @Test
        fun `a context can require check-in comments`() {
            val obj = newPart()
            preferences.setForContext(manager, product, "versioning.checkInCommentRequired", true, locked = false)
            versioning.checkOut(bob, obj.id)

            assertCode(
                ValidationException::class.java,
                "CHECK_IN_COMMENT_REQUIRED",
            ) { versioning.checkIn(bob, obj.id, "  ") }
            assertThat(versioning.checkIn(bob, obj.id, "Thicker flange").latest.comment).isEqualTo("Thicker flange")
        }

        @Test
        fun `a context's numbering scheme numbers types that have none`() {
            val prefix =
                "P" +
                    UUID
                        .randomUUID()
                        .toString()
                        .take(4)
                        .uppercase() + "-"
            val scheme = typeRules.createNumberingScheme(admin, CreateNumberingScheme("Ctx $prefix", prefix, 3))
            preferences.setForContext(manager, product, "numbering.defaultScheme", scheme.name, locked = false)

            assertThat(newPart().number).isEqualTo("${prefix}001")
            assertThat(newPart().number).isEqualTo("${prefix}002")
            assertCode(ValidationException::class.java, "INVALID_PREFERENCE_VALUE") {
                preferences.setForContext(manager, product, "numbering.defaultScheme", "No such scheme", locked = false)
            }
        }
    }

    @Test
    fun `abstract types can't be created`() {
        val type =
            types.createSubtype(
                admin,
                CreateSubtype(
                    TypeId.PART,
                    "Abs" +
                        UUID
                            .randomUUID()
                            .toString()
                            .take(8)
                            .replace("-", ""),
                    "Hardware",
                ),
            )
        types.update(admin, type.id, TypeProperties("Hardware", instantiable = false))

        assertCode(ValidationException::class.java, "TYPE_NOT_INSTANTIABLE") {
            versioning.create(alice, CreateVersionedObject(type.id, product, "Nope"))
        }
    }

    @Nested
    inner class RenameAndRenumber {
        @Test
        fun `a manager renames and renumbers, for every version, and the old identity stays in the history`() {
            val obj = newPart()
            release(obj.id)
            versioning.revise(alice, obj.id)
            val newNumber = "BRK-${UUID.randomUUID().toString().take(8)}"

            val renamed = versioning.rename(manager, obj.id, Rename(newNumber, "Motor bracket", "Align with ERP"))

            assertThat(renamed.number).isEqualTo(newNumber)
            assertThat(renamed.name).isEqualTo("Motor bracket")
            assertThat(versioning.get(alice, obj.id).number).isEqualTo(newNumber)
            assertThat(versioning.history(alice, obj.id)).hasSize(2) // A.1 and B.1, both under the new identity
            val event =
                jdbc
                    .sql(
                        "select event_type || ' ' || payload::text from outbox_event where aggregate_id = :id order by seq desc limit 1",
                    ).param("id", obj.id.value)
                    .query(String::class.java)
                    .single()
            assertThat(event).contains("versioning.ObjectRenamed.v1", obj.number, "Bracket", "Align with ERP")
        }

        @Test
        fun `renaming needs MANAGE, a free number and no check-out`() {
            val obj = newPart()
            val other = newPart()

            assertCode(
                ForbiddenException::class.java,
                "ACCESS_DENIED",
            ) { versioning.rename(alice, obj.id, Rename(name = "Mine")) }
            assertCode(NotFoundException::class.java, "OBJECT_NOT_FOUND") {
                versioning.rename(outsider, obj.id, Rename(name = "X"))
            }
            assertCode(ConflictException::class.java, "DUPLICATE_NUMBER") {
                versioning.rename(manager, obj.id, Rename(number = other.number))
            }
            assertCode(ValidationException::class.java, "INVALID_NUMBER") {
                versioning.rename(manager, obj.id, Rename(number = "bad number!"))
            }
            assertCode(ValidationException::class.java, "NO_CHANGE") {
                versioning.rename(manager, obj.id, Rename(obj.number, obj.name))
            }

            versioning.checkOut(bob, obj.id)
            assertCode(
                ConflictException::class.java,
                "CHECKED_OUT",
            ) { versioning.rename(manager, obj.id, Rename(name = "Later")) }
            versioning.undoCheckOut(bob, obj.id)
            assertThat(versioning.rename(manager, obj.id, Rename(name = "Later")).name).isEqualTo("Later")
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

    @Nested
    inner class ValidationRules {
        private val bracket by lazy {
            types
                .createSubtype(
                    admin,
                    CreateSubtype(TypeId.PART, "Ruled${UUID.randomUUID().toString().take(8)}", "Ruled bracket"),
                ).id
        }

        private fun create(attributes: Map<String, Any?>) =
            versioning.create(alice, CreateVersionedObject(bracket, product, "Bracket", attributes = attributes))

        @Test
        fun `rules of the type and its parents are checked on create, all failures at once`() {
            validationRules.define(
                admin,
                bracket,
                ValidationRuleSettings(
                    "Bought parts are described",
                    "object.attributes.source != 'buy' || object.attributes.description != null",
                    "Describe parts you buy",
                    attribute = "description",
                ),
            )
            validationRules.define(
                admin,
                bracket,
                ValidationRuleSettings("Named sensibly", "size(object.name) > 3", "Give it a longer name"),
            )

            val error =
                catchThrowableOfType(ValidationException::class.java) {
                    versioning.create(
                        alice,
                        CreateVersionedObject(
                            bracket,
                            product,
                            "Br",
                            attributes =
                                mapOf(
                                    "source" to "buy",
                                ),
                        ),
                    )
                }
            assertThat(error.code).isEqualTo("INVALID_ATTRIBUTES")
            @Suppress("UNCHECKED_CAST")
            val errors = error.details["errors"] as List<Map<String, Any?>>
            assertThat(errors.map { it["attribute"] }).containsExactly("description", "Named sensibly")

            assertThat(create(mapOf("source" to "buy", "description" to "M8 hex")).latest.version).isEqualTo("A.1")
            assertThat(create(mapOf("source" to "make")).latest.version).isEqualTo("A.1")
        }

        @Test
        fun `rules are checked on check-in, not while editing, and inactive ones are skipped`() {
            val rule =
                validationRules.define(
                    admin,
                    bracket,
                    ValidationRuleSettings(
                        "Material set",
                        "object.attributes.material != null",
                        "Pick a material",
                        "material",
                    ),
                )
            val part = create(mapOf("material" to "AL6061"))
            versioning.checkOut(alice, part.id)
            versioning.updateWorkingCopy(alice, part.id, mapOf("material" to null)) // a draft may break it
            assertCode(ValidationException::class.java, "INVALID_ATTRIBUTES") { versioning.checkIn(alice, part.id) }

            validationRules.update(
                admin,
                rule.id,
                ValidationRuleSettings("Material set", rule.condition, rule.message, "material", active = false),
            )
            assertThat(versioning.checkIn(alice, part.id).latest.version).isEqualTo("A.2")
        }

        @Test
        fun `a rule that can't be evaluated fails closed, and bad rules are refused`() {
            validationRules.define(
                admin,
                bracket,
                ValidationRuleSettings("Broken", "object.attributes.nosuch == 1", "Never"),
            )
            assertCode(ValidationException::class.java, "INVALID_ATTRIBUTES") { create(emptyMap()) }

            assertCode(ValidationException::class.java, "INVALID_EXPRESSION") {
                validationRules.define(admin, bracket, ValidationRuleSettings("Bad", "object.name ==", "x"))
            }
            assertCode(ValidationException::class.java, "UNKNOWN_ATTRIBUTE") {
                validationRules.define(
                    admin,
                    bracket,
                    ValidationRuleSettings("Field", "true", "x", attribute = "nosuch"),
                )
            }
            assertCode(ForbiddenException::class.java, "ADMIN_REQUIRED") {
                validationRules.define(alice, bracket, ValidationRuleSettings("Mine", "true", "x"))
            }
        }
    }

    @Nested
    inner class CalculatedAttributes {
        private val kit by lazy {
            types
                .createSubtype(
                    admin,
                    CreateSubtype(TypeId.PART, "Kit${UUID.randomUUID().toString().take(8)}", "Kit"),
                ).id
                .also { kit ->
                    fun define(
                        name: String,
                        type: DataType,
                        formula: String? = null,
                    ) = attributeDefinitions.define(
                        admin,
                        DefineAttribute(
                            kit,
                            name,
                            type,
                            settings = AttributeSettings(mapOf("en" to name), formula = formula),
                        ),
                    )
                    define("quantity", DataType.INTEGER)
                    define("unitCost", DataType.REAL)
                    define(
                        "totalCost",
                        DataType.REAL,
                        "double(object.attributes.quantity) * object.attributes.unitCost",
                    )
                    // Uses the previous formula's result, and the object's number.
                    define("summary", DataType.STRING, "object.number + ': ' + string(object.attributes.totalCost)")
                }
        }

        private fun create(attributes: Map<String, Any?>) =
            versioning.create(alice, CreateVersionedObject(kit, product, "Kit", attributes = attributes))

        @Test
        fun `formulas compute values on create, and users can't set them`() {
            val obj = create(mapOf("quantity" to 4, "unitCost" to 2.5, "totalCost" to 999))
            assertThat(obj.latest.attributes["totalCost"]).isEqualTo(10.0)
            assertThat(obj.latest.attributes["summary"]).isEqualTo("${obj.number}: 10.0")
        }

        @Test
        fun `a formula without what it needs leaves the value empty`() {
            val obj = create(mapOf("quantity" to 4))
            assertThat(obj.latest.attributes).doesNotContainKey("totalCost")
        }

        @Test
        fun `editing the working copy and checking in recompute them`() {
            val obj = create(mapOf("quantity" to 4, "unitCost" to 2.5))
            versioning.checkOut(alice, obj.id)
            val edited = versioning.updateWorkingCopy(alice, obj.id, mapOf("quantity" to 10, "unitCost" to 2.5))
            assertThat(edited.attributes["totalCost"]).isEqualTo(25.0)
            assertThat(versioning.checkIn(alice, obj.id).latest.attributes["totalCost"]).isEqualTo(25.0)
        }

        @Test
        fun `a calculated attribute can't also be required, and its formula must be valid`() {
            assertCode(ValidationException::class.java, "INVALID_ATTRIBUTE_DEFINITION") {
                attributeDefinitions.define(
                    admin,
                    DefineAttribute(
                        kit,
                        "bad",
                        DataType.REAL,
                        settings = AttributeSettings(mapOf("en" to "Bad"), required = true, formula = "1.0"),
                    ),
                )
            }
            assertCode(ValidationException::class.java, "INVALID_ATTRIBUTE_DEFINITION") {
                attributeDefinitions.define(
                    admin,
                    DefineAttribute(
                        kit,
                        "bad2",
                        DataType.REAL,
                        settings = AttributeSettings(mapOf("en" to "Bad"), formula = "1.0 +"),
                    ),
                )
            }
        }
    }

    @Nested
    inner class Gates {
        private fun gatedType(): TypeId {
            val type =
                types
                    .createSubtype(
                        admin,
                        CreateSubtype(TypeId.PART, "Gate${UUID.randomUUID().toString().take(8)}", "Gated"),
                    ).id
            val lc =
                lifecycles.create(
                    admin,
                    CreateLifecycleTemplate(
                        "Gate ${UUID.randomUUID()}",
                        mapOf("en" to "Gate"),
                        "DRAFT",
                        listOf(
                            LifecycleState("DRAFT", mapOf("en" to "Draft"), editable = true, revisable = false),
                            LifecycleState(
                                "RELEASED",
                                mapOf("en" to "Released"),
                                editable = false,
                                revisable = true,
                                entryCondition =
                                    "object.attributes.source == 'make' || " +
                                        "object.attributes.description != null",
                                entryMessage = "Bought parts need a description before release",
                            ),
                        ),
                        listOf(LifecycleTransition("DRAFT", "RELEASED")),
                    ),
                )
            lifecycles.assign(admin, type, lc.id)
            return type
        }

        @Test
        fun `a state's entry condition must hold, with its message, unless an admin overrides`() {
            val type = gatedType()
            val bought =
                versioning.create(
                    alice,
                    CreateVersionedObject(
                        type,
                        product,
                        "Bought",
                        attributes =
                            mapOf(
                                "source" to "buy",
                            ),
                    ),
                )
            assertThatThrownBy { versioning.setState(manager, bought.id, SetState("RELEASED")) }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo("ENTRY_CRITERIA_NOT_MET")
                    assertThat(it.message).isEqualTo("Bought parts need a description before release")
                }
            assertThat(
                versioning.setState(admin, bought.id, SetState("RELEASED", override = true)).latest.state,
            ).isEqualTo("RELEASED")

            val made =
                versioning.create(
                    alice,
                    CreateVersionedObject(
                        type,
                        product,
                        "Made",
                        attributes =
                            mapOf(
                                "source" to "make",
                            ),
                    ),
                )
            assertThat(versioning.setState(manager, made.id, SetState("RELEASED")).latest.state).isEqualTo("RELEASED")
        }

        @Test
        fun `an invalid entry condition, or one without a message, is refused`() {
            assertThatThrownBy {
                lifecycles.create(
                    admin,
                    CreateLifecycleTemplate(
                        "Bad ${UUID.randomUUID()}",
                        mapOf("en" to "Bad"),
                        "A",
                        listOf(
                            LifecycleState("A", mapOf("en" to "A"), editable = true, revisable = false),
                            LifecycleState(
                                "B",
                                mapOf("en" to "B"),
                                editable = false,
                                revisable = true,
                                entryCondition = "object.state ==",
                            ),
                        ),
                        listOf(LifecycleTransition("A", "B")),
                    ),
                )
            }.isInstanceOf(ValidationException::class.java).hasMessageContaining("entry condition isn't valid")
        }
    }
}
