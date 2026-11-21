package com.plm.change

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.change.api.AddChangeLink
import com.plm.change.api.ChangeRole
import com.plm.change.api.ChangeService
import com.plm.change.api.ChangeSetupService
import com.plm.change.api.SaveAssociationRule
import com.plm.change.api.SaveChangeTemplate
import com.plm.change.api.UseChangeTemplate
import com.plm.change.api.VarianceKind
import com.plm.change.api.VarianceScope
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.time.LocalDate
import java.util.UUID

/** Change templates, association rules and variances (roadmap 5.10). */
@PlmIntegrationTest
internal class ChangeSetupIT(
    @Autowired private val setup: ChangeSetupService,
    @Autowired private val changes: ChangeService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Setup ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    @Test
    fun `a template makes a notice with its standard tasks`() {
        val p = product()
        val template =
            setup.saveTemplate(
                admin,
                null,
                SaveChangeTemplate(
                    "Supplier change ${UUID.randomUUID().toString().take(5)}",
                    TypeId.CHANGE_NOTICE.value,
                    "Supplier change",
                    tasks = listOf("Qualify the new supplier", "Update the purchasing data"),
                ),
            )
        val cn = setup.useTemplate(alice, template.id, UseChangeTemplate(p.value))
        assertThat(versioning.get(alice, MasterId(cn)).name).isEqualTo("Supplier change")
        assertThat(changes.view(alice, cn).plan.map { it.task.name })
            .containsExactlyInAnyOrder("Qualify the new supplier", "Update the purchasing data")
        assertThatThrownBy { setup.saveTemplate(alice, null, SaveChangeTemplate("Mine", TypeId.CHANGE_NOTICE.value)) }
            .hasMessageContaining("administrators")
    }

    @Test
    fun `association rules limit what a kind of change may be linked to`() {
        val p = product()
        // A dedicated subtype, so the rule touches only it.
        val docOnly =
            versioning.create(alice, CreateVersionedObject(TypeId.PROBLEM_REPORT, p, "Typo in the manual"))
        val rule =
            setup.addRule(
                admin,
                SaveAssociationRule(TypeId.PROBLEM_REPORT.value, ChangeRole.AFFECTED, TypeId.DOCUMENT.value),
            )
        try {
            val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Bolt"))
            val manual = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, p, "Manual"))
            assertThatThrownBy {
                changes.link(
                    alice,
                    docOnly.id.value,
                    AddChangeLink(part.id.value, ChangeRole.AFFECTED),
                )
            }.isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Only Document")
            changes.link(alice, docOnly.id.value, AddChangeLink(manual.id.value, ChangeRole.AFFECTED))
        } finally {
            setup.deleteRule(admin, rule.id)
        }
    }

    @Test
    fun `an approved variance is in force on what it affects until its date`() {
        val p = product()
        val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Housing"))
        val variance = versioning.create(alice, CreateVersionedObject(TypeId.VARIANCE, p, "Use 6061 instead of 7075"))
        assertThat(variance.number).startsWith("VAR-")
        changes.link(alice, variance.id.value, AddChangeLink(part.id.value, ChangeRole.AFFECTED))
        assertThatThrownBy { setup.setVariance(alice, variance.id.value, VarianceScope(VarianceKind.DEVIATION)) }
            .hasMessageContaining("Limit it")
        setup.setVariance(
            alice,
            variance.id.value,
            VarianceScope(VarianceKind.DEVIATION, LocalDate.of(2099, 1, 1), 50, "Supply shortage"),
        )
        assertThat(setup.activeVariances(alice, part.id.value)).isEmpty()
        versioning.setState(admin, variance.id, SetState("UNDER_REVIEW"))
        versioning.setState(admin, variance.id, SetState("APPROVED"))
        val active = setup.activeVariances(alice, part.id.value).single()
        assertThat(active.number).isEqualTo(variance.number)
        assertThat(active.scope.quantity).isEqualTo(50)
    }
}
