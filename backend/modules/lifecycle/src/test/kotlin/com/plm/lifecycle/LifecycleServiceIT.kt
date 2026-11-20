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
}
