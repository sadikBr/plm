package com.plm.types

import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeSettings
import com.plm.types.api.CreateSubtype
import com.plm.types.api.DataType
import com.plm.types.api.DefineAttribute
import com.plm.types.api.LayoutGroup
import com.plm.types.api.LayoutScreen
import com.plm.types.api.LayoutService
import com.plm.types.api.LayoutSource
import com.plm.types.api.SaveLayout
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class LayoutServiceIT(
    @Autowired private val types: TypeService,
    @Autowired private val attributes: AttributeService,
    @Autowired private val layouts: LayoutService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun unique(prefix: String) =
        prefix +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)

    private fun fastener(): TypeId {
        val type = types.createSubtype(admin, CreateSubtype(TypeId.PART, unique("Fastener"), "Fastener")).id
        attributes.define(
            admin,
            DefineAttribute(
                type,
                "threadSize",
                DataType.STRING,
                settings =
                    AttributeSettings(
                        mapOf(
                            "en" to "Thread size",
                        ),
                    ),
            ),
        )
        return type
    }

    @Test
    fun `parts come with an out-of-the-box layout`() {
        val resolved = layouts.resolve(alice, TypeId.PART, LayoutScreen.CREATE)

        assertThat(resolved.source).isEqualTo(LayoutSource(TypeId.PART, LayoutScreen.DEFAULT))
        assertThat(resolved.groups.map { it.key }).startsWith("general", "structure")
        assertThat(resolved.groups.first().attributes).startsWith("description", "source")
    }

    @Test
    fun `a subtype inherits the parent's layout, with its own attributes in a trailing group`() {
        val resolved = layouts.resolve(alice, fastener(), LayoutScreen.INFO)

        assertThat(resolved.source).isEqualTo(LayoutSource(TypeId.PART, LayoutScreen.DEFAULT))
        assertThat(resolved.groups.last().key).isEqualTo("more")
        assertThat(resolved.groups.last().attributes).contains("threadSize")
    }

    @Test
    fun `an admin saves a layout for one screen and removes it again`() {
        val type = fastener()
        val saved =
            layouts.save(
                admin,
                type,
                LayoutScreen.CREATE,
                SaveLayout(
                    listOf(LayoutGroup("thread", mapOf("en" to "Thread"), 1, listOf("threadSize", "material"))),
                    hidden = listOf("gatheringPart"),
                ),
            )

        assertThat(layouts.own(alice, type)).containsExactly(saved)
        val create = layouts.resolve(alice, type, LayoutScreen.CREATE)
        assertThat(create.source).isEqualTo(LayoutSource(type, LayoutScreen.CREATE))
        assertThat(create.groups.first().attributes).containsExactly("threadSize", "material")
        assertThat(create.groups.flatMap { it.attributes }).doesNotContain("gatheringPart").contains("mass")
        // Other screens still inherit.
        assertThat(layouts.resolve(alice, type, LayoutScreen.EDIT).source?.typeId).isEqualTo(TypeId.PART)

        layouts.remove(admin, type, LayoutScreen.CREATE)
        assertThat(layouts.resolve(alice, type, LayoutScreen.CREATE).source?.typeId).isEqualTo(TypeId.PART)
        assertThatThrownBy { layouts.remove(admin, type, LayoutScreen.CREATE) }
            .isInstanceOfSatisfying(NotFoundException::class.java) { assertThat(it.code).isEqualTo("LAYOUT_NOT_FOUND") }
    }

    @Test
    fun `invalid layouts and non-admins are refused`() {
        val type = fastener()
        val bad = SaveLayout(listOf(LayoutGroup("main", mapOf("en" to "Main"), 2, listOf("nope"))))

        assertThatThrownBy { layouts.save(admin, type, LayoutScreen.DEFAULT, bad) }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.code).isEqualTo("INVALID_LAYOUT")
                assertThat(
                    it.details["errors"] as List<*>,
                ).containsExactly("Group 'main': no attribute 'nope' on this type")
            }
        assertThatThrownBy { layouts.save(alice, type, LayoutScreen.DEFAULT, bad) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { layouts.resolve(alice, TypeId(UUID.randomUUID()), LayoutScreen.INFO) }
            .isInstanceOfSatisfying(NotFoundException::class.java) { assertThat(it.code).isEqualTo("TYPE_NOT_FOUND") }
    }
}
