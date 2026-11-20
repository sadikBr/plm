package com.plm.types

import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.CreateNumberingScheme
import com.plm.types.api.CreateSubtype
import com.plm.types.api.NumberingSchemeId
import com.plm.types.api.RevisionScheme
import com.plm.types.api.TypeId
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeRuleSettings
import com.plm.types.api.TypeService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class TypeRuleServiceIT(
    @Autowired private val types: TypeService,
    @Autowired private val rules: TypeRuleService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun subtype(parent: TypeId) =
        types
            .createSubtype(
                admin,
                CreateSubtype(
                    parent,
                    "T" +
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .take(10),
                    "T",
                ),
            ).id

    @Test
    fun `out-of-the-box rules`() {
        val part = rules.rules(alice, TypeId.PART)

        assertThat(part.revisionScheme).isEqualTo(RevisionScheme.ALPHABETIC_ASME)
        assertThat(part.allowManualNumber).isTrue()
        assertThat(part.numberingSchemeId).isNull()
        assertThat(part.inheritedFrom).containsEntry("revisionScheme", TypeId.PART)
    }

    @Test
    fun `subtypes inherit each rule from the nearest type that sets it`() {
        val mid = subtype(TypeId.PART)
        val leaf = subtype(mid)
        val scheme = rules.createNumberingScheme(admin, CreateNumberingScheme("S ${UUID.randomUUID()}", prefix = "M-"))
        rules.updateRules(
            admin,
            mid,
            TypeRuleSettings(numberingSchemeId = scheme.id, revisionScheme = RevisionScheme.NUMERIC_FROM_1),
        )
        rules.updateRules(admin, leaf, TypeRuleSettings(allowManualNumber = false))

        val effective = rules.rules(alice, leaf)

        assertThat(effective.numberingSchemeId).isEqualTo(scheme.id)
        assertThat(effective.revisionScheme).isEqualTo(RevisionScheme.NUMERIC_FROM_1)
        assertThat(effective.allowManualNumber).isFalse()
        assertThat(effective.inheritedFrom).isEqualTo(
            mapOf(
                "numberingScheme" to mid,
                "revisionScheme" to mid,
                "allowManualNumber" to leaf,
            ),
        )
    }

    @Test
    fun `numbering schemes format and count`() {
        val scheme =
            rules.createNumberingScheme(
                admin,
                CreateNumberingScheme(
                    "D ${UUID.randomUUID()}",
                    prefix = "DOC-",
                    padding = 5,
                    suffix = "-X",
                    start = 42,
                ),
            )

        assertThat(scheme.example).isEqualTo("DOC-00042-X")
        assertThat(rules.nextNumber(alice, scheme.id)).isEqualTo("DOC-00042-X")
        assertThat(rules.nextNumber(alice, scheme.id)).isEqualTo("DOC-00043-X")
        assertThat(rules.numberingSchemes(alice).single { it.id == scheme.id }.nextValue).isEqualTo(44)
    }

    @Test
    fun `changes are validated and admin-only`() {
        assertThatThrownBy {
            rules.createNumberingScheme(admin, CreateNumberingScheme("bad", prefix = "has space", padding = 0))
        }.isInstanceOfSatisfying(
            ValidationException::class.java,
        ) { assertThat(it.code).isEqualTo("INVALID_NUMBERING_SCHEME") }
        assertThatThrownBy { rules.updateRules(alice, TypeId.PART, TypeRuleSettings(allowManualNumber = false)) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ADMIN_REQUIRED") }
        assertThatThrownBy {
            rules.updateRules(
                admin,
                TypeId.PART,
                TypeRuleSettings(numberingSchemeId = NumberingSchemeId(UUID.randomUUID())),
            )
        }.isInstanceOfSatisfying(
            NotFoundException::class.java,
        ) { assertThat(it.code).isEqualTo("NUMBERING_SCHEME_NOT_FOUND") }
    }
}
