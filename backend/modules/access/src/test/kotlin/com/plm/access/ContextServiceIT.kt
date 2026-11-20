package com.plm.access

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class ContextServiceIT(
    @Autowired private val contexts: ContextService,
) {
    private val actor = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun org() =
        contexts.create(
            actor,
            CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Org ${UUID.randomUUID()}"),
        )

    @Test
    fun `builds the site - organization - product hierarchy`() {
        val org = org()
        val product = contexts.create(actor, CreateContext(org.id, ContextKind.PRODUCT, "Drone X1"))

        assertThat(product.parentId).isEqualTo(org.id)
        assertThat(product.createdBy).isEqualTo("alice")
        assertThat(contexts.children(actor, org.id).map { it.name }).containsExactly("Drone X1")
        assertThat(contexts.get(actor, ContextId.DEFAULT_SITE).kind).isEqualTo(ContextKind.SITE)
    }

    @Test
    fun `enforces which kinds may nest`() {
        val org = org()
        val library = contexts.create(actor, CreateContext(org.id, ContextKind.LIBRARY, "Fasteners"))

        assertThatThrownBy { contexts.create(actor, CreateContext(ContextId.DEFAULT_SITE, ContextKind.PRODUCT, "P")) }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.code).isEqualTo("INVALID_CONTEXT_HIERARCHY")
            }
        assertThatThrownBy { contexts.create(actor, CreateContext(library.id, ContextKind.PRODUCT, "P")) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `sibling names are unique`() {
        val org = org()
        contexts.create(actor, CreateContext(org.id, ContextKind.PRODUCT, "Same"))

        assertThatThrownBy { contexts.create(actor, CreateContext(org.id, ContextKind.PROJECT, "Same")) }
            .isInstanceOfSatisfying(ConflictException::class.java) {
                assertThat(it.code).isEqualTo("DUPLICATE_CONTEXT_NAME")
            }
    }
}
