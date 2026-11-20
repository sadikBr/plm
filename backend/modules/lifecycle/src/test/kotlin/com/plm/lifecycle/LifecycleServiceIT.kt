package com.plm.lifecycle

import com.plm.lifecycle.api.CreateLifecycleTemplate
import com.plm.lifecycle.api.LifecycleService
import com.plm.lifecycle.api.LifecycleState
import com.plm.lifecycle.api.LifecycleTemplateId
import com.plm.lifecycle.api.LifecycleTransition
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.CreateSubtype
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class LifecycleServiceIT(
    @Autowired private val lifecycles: LifecycleService,
    @Autowired private val types: TypeService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun state(
        key: String,
        editable: Boolean = false,
        revisable: Boolean = false,
    ) = LifecycleState(key, mapOf("en" to key.lowercase()), editable, revisable)

    private fun template(
        initial: String,
        states: List<LifecycleState>,
        transitions: List<LifecycleTransition>,
    ) = CreateLifecycleTemplate("L ${UUID.randomUUID()}", mapOf("en" to "L"), initial, states, transitions)

    @Test
    fun `parts and documents use the Basic lifecycle out of the box`() {
        val basic = lifecycles.forType(alice, TypeId.PART)

        assertThat(basic.id).isEqualTo(LifecycleTemplateId.BASIC)
        assertThat(
            basic.states.map { it.key },
        ).containsExactly("IN_WORK", "UNDER_REVIEW", "RELEASED", "OBSOLETE", "CANCELED")
        assertThat(basic.states.filter { it.editable }.map { it.key }).containsExactly("IN_WORK")
        assertThat(basic.states.filter { it.revisable }.map { it.key }).containsExactly("RELEASED")
        assertThat(basic.nextStates("UNDER_REVIEW").map { it.key }).containsExactly("IN_WORK", "RELEASED")
        assertThat(lifecycles.forType(alice, TypeId.DOCUMENT).id).isEqualTo(LifecycleTemplateId.BASIC)
    }

    @Test
    fun `subtypes inherit the nearest assignment`() {
        val mid =
            types
                .createSubtype(
                    admin,
                    CreateSubtype(
                        TypeId.PART,
                        "M" +
                            UUID
                                .randomUUID()
                                .toString()
                                .replace("-", "")
                                .take(8),
                        "M",
                    ),
                ).id
        val leaf =
            types
                .createSubtype(
                    admin,
                    CreateSubtype(
                        mid,
                        "L" +
                            UUID
                                .randomUUID()
                                .toString()
                                .replace("-", "")
                                .take(8),
                        "L",
                    ),
                ).id
        val custom =
            lifecycles.create(
                admin,
                template(
                    "DRAFT",
                    listOf(state("DRAFT", editable = true), state("DONE", revisable = true)),
                    listOf(LifecycleTransition("DRAFT", "DONE")),
                ),
            )

        lifecycles.assign(admin, mid, custom.id)

        assertThat(lifecycles.forType(alice, leaf).id).isEqualTo(custom.id)
        assertThat(lifecycles.forType(alice, TypeId.PART).id).isEqualTo(LifecycleTemplateId.BASIC)
    }

    @Test
    fun `templates must be usable`() {
        val invalid =
            listOf(
                // initial state not editable
                template("A", listOf(state("A"), state("B", editable = true)), listOf(LifecycleTransition("A", "B"))),
                // unreachable state
                template("A", listOf(state("A", editable = true), state("B")), emptyList()),
                // unknown state in a transition
                template("A", listOf(state("A", editable = true)), listOf(LifecycleTransition("A", "Z"))),
                // bad key
                template("a", listOf(state("a", editable = true)), emptyList()),
            )

        invalid.forEach { t ->
            assertThatThrownBy { lifecycles.create(admin, t) }
                .describedAs(t.toString())
                .isInstanceOfSatisfying(
                    ValidationException::class.java,
                ) { assertThat(it.code).isEqualTo("INVALID_LIFECYCLE") }
        }
    }

    @Test
    fun `only site administrators create and assign`() {
        val t = template("A", listOf(state("A", editable = true)), emptyList())

        assertThatThrownBy { lifecycles.create(alice, t) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy {
            lifecycles.assign(alice, TypeId.PART, LifecycleTemplateId.BASIC)
        }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `editing a lifecycle saves a new version that types move to`() {
        val v1 =
            lifecycles.create(
                admin,
                template(
                    "DRAFT",
                    listOf(state("DRAFT", editable = true), state("DONE", revisable = true)),
                    listOf(LifecycleTransition("DRAFT", "DONE")),
                ),
            )
        val type =
            types
                .createSubtype(
                    admin,
                    CreateSubtype(
                        TypeId.PART,
                        "V" +
                            UUID
                                .randomUUID()
                                .toString()
                                .take(8)
                                .replace("-", ""),
                        "Versioned",
                    ),
                ).id
        lifecycles.assign(admin, type, v1.id)

        val v2 =
            lifecycles.update(
                admin,
                v1.id,
                template(
                    "DRAFT",
                    listOf(state("DRAFT", editable = true), state("REVIEW"), state("DONE", revisable = true)),
                    listOf(LifecycleTransition("DRAFT", "REVIEW"), LifecycleTransition("REVIEW", "DONE")),
                ),
            )

        assertThat(v2.version).isEqualTo(2)
        assertThat(v2.name).isEqualTo(v1.name) // the name stays
        assertThat(lifecycles.forType(alice, type).id).isEqualTo(v2.id)
        assertThat(lifecycles.list(alice).map { it.id }).contains(v2.id).doesNotContain(v1.id)
        assertThat(lifecycles.get(alice, v1.id).latest).isFalse() // old versions stay readable
        assertThat(lifecycles.versions(alice, v1.id).map { it.version }).containsExactly(2, 1)
        // Assigning an old version means its current one.
        lifecycles.assign(admin, type, v1.id)
        assertThat(lifecycles.forType(alice, type).id).isEqualTo(v2.id)
        assertThatThrownBy {
            lifecycles.update(admin, v1.id, template("DRAFT", listOf(state("DRAFT", editable = true)), emptyList()))
        }.isInstanceOfSatisfying(com.plm.platform.error.ConflictException::class.java) {
            assertThat(it.code).isEqualTo("NOT_LATEST_VERSION")
        }
        assertThatThrownBy {
            lifecycles.update(alice, v2.id, template("DRAFT", listOf(state("DRAFT", editable = true)), emptyList()))
        }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `entry criteria and revision series are validated`() {
        val bad =
            template(
                "DRAFT",
                listOf(
                    state("DRAFT", editable = true).copy(requiredAttributes = listOf("Not a name")),
                    state("DONE").copy(revisionScheme = com.plm.types.api.RevisionScheme.NUMERIC_FROM_1),
                ),
                listOf(LifecycleTransition("DRAFT", "DONE")),
            )
        assertThatThrownBy { lifecycles.create(admin, bad) }.isInstanceOfSatisfying(ValidationException::class.java) {
            assertThat(it.details["errors"] as List<*>).containsExactly(
                "State 'DRAFT': 'Not a name' is not an attribute name",
                "State 'DONE' sets a revision series but can't be revised from",
            )
        }
    }
}
