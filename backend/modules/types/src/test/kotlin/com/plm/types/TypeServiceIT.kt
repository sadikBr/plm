package com.plm.types

import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.BaseClass
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
class TypeServiceIT(
    @Autowired private val types: TypeService,
) {
    private val actor = Actor(TenantId.DEFAULT, PrincipalId("admin"))

    private fun uniqueName(prefix: String) =
        prefix +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)

    @Test
    fun `built-in base types are seeded`() {
        assertThat(types.get(actor, TypeId.DOCUMENT).baseClass).isEqualTo(BaseClass.DOCUMENT)
        assertThat(types.get(actor, TypeId.PART).baseClass).isEqualTo(BaseClass.PART)
    }

    @Test
    fun `subtype inherits the parent's base class`() {
        val mechanical = types.createSubtype(actor, CreateSubtype(TypeId.PART, uniqueName("Mech"), "Mechanical Part"))
        val fastener = types.createSubtype(actor, CreateSubtype(mechanical.id, uniqueName("Fast"), "Fastener"))

        assertThat(fastener.baseClass).isEqualTo(BaseClass.PART)
        assertThat(fastener.parentId).isEqualTo(mechanical.id)
        assertThat(types.list(actor).map { it.id }).contains(mechanical.id, fastener.id)
    }

    @Test
    fun `type names are unique per tenant`() {
        val name = uniqueName("Spec")
        types.createSubtype(actor, CreateSubtype(TypeId.DOCUMENT, name, "Spec"))

        assertThatThrownBy { types.createSubtype(actor, CreateSubtype(TypeId.DOCUMENT, name, "Spec again")) }
            .isInstanceOfSatisfying(
                ConflictException::class.java,
            ) { assertThat(it.code).isEqualTo("DUPLICATE_TYPE_NAME") }
    }

    @Test
    fun `rejects invalid names and unknown parents`() {
        assertThatThrownBy { types.createSubtype(actor, CreateSubtype(TypeId.PART, "1bad name", "Bad")) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { types.createSubtype(actor, CreateSubtype(TypeId(UUID.randomUUID()), "Orphan", "Orphan")) }
            .isInstanceOfSatisfying(NotFoundException::class.java) { assertThat(it.code).isEqualTo("TYPE_NOT_FOUND") }
    }
}
