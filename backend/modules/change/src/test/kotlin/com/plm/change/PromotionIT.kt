package com.plm.change

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.change.api.CreatePromotion
import com.plm.change.api.PromotionService
import com.plm.change.api.PromotionStatus
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.CompleteWorkItem
import com.plm.workflow.api.NodeKind
import com.plm.workflow.api.ProcessDefinition
import com.plm.workflow.api.ProcessNode
import com.plm.workflow.api.ProcessTemplateService
import com.plm.workflow.api.SaveProcessTemplate
import com.plm.workflow.api.WorkItemService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Promotion requests (roadmap 5.7): reviewed, locked meanwhile, promoted on approval. */
@PlmIntegrationTest
internal class PromotionIT(
    @Autowired private val promotions: PromotionService,
    @Autowired private val templates: ProcessTemplateService,
    @Autowired private val items: WorkItemService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val mona = Actor(TenantId.DEFAULT, PrincipalId("mona-manager"))

    private val review by lazy {
        templates
            .create(
                admin,
                SaveProcessTemplate(
                    "Promo ${UUID.randomUUID().toString().take(6)}",
                    definition =
                        ProcessDefinition(
                            "ok",
                            listOf(
                                ProcessNode(
                                    "ok",
                                    NodeKind.TASK,
                                    "Approve promotion",
                                    assignees = listOf("role:MANAGER"),
                                    choices = listOf("APPROVE", "REJECT"),
                                    outcomes = mapOf("APPROVE" to "yes", "REJECT" to "no"),
                                ),
                                ProcessNode("yes", NodeKind.END, outcome = "APPROVED"),
                                ProcessNode("no", NodeKind.END, outcome = "REJECTED"),
                            ),
                        ),
                ),
            ).id
    }

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Promo ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
            teams.put(admin, it, mona.principal, TeamRole.MANAGER)
        }
    }

    private fun answer(
        request: UUID,
        outcome: String,
    ) {
        val instance = promotions.get(alice, request).instanceId
        var task = items.list(mona).firstOrNull { it.instanceId == instance }
        repeat(100) {
            if (task == null) {
                Thread.sleep(100)
                task = items.list(mona).firstOrNull { it.instanceId == instance }
            }
        }
        items.complete(mona, task!!.id, CompleteWorkItem(outcome))
    }

    private fun decided(id: UUID): PromotionStatus {
        repeat(100) {
            val s = promotions.get(alice, id).status
            if (s != PromotionStatus.UNDER_REVIEW) return s
            Thread.sleep(100)
        }
        return PromotionStatus.UNDER_REVIEW
    }

    @Test
    fun `approval promotes every object of the request, which stay locked until then`() {
        val p = product()
        val a = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Bracket"))
        val b = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, p, "Bracket drawing"))
        val req =
            promotions.create(
                alice,
                CreatePromotion("Bracket release", "RELEASED", listOf(a.id.value, b.id.value), templateId = review),
            )
        assertThat(req.number).startsWith("PRM-")
        assertThat(req.items.map { it.stateBefore }).containsOnly("IN_WORK")
        assertThatThrownBy {
            versioning.checkOut(alice, a.id)
        }.isInstanceOf(PlmException::class.java).hasMessageContaining(req.number)
        assertThatThrownBy {
            promotions.create(alice, CreatePromotion("Again", "RELEASED", listOf(a.id.value), templateId = review))
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("already in ${req.number}")

        answer(req.id, "APPROVE")
        assertThat(decided(req.id)).isEqualTo(PromotionStatus.APPROVED)
        assertThat(promotions.get(alice, req.id).items.map { it.stateNow }).containsOnly("RELEASED")
        assertThat(promotions.forObject(alice, a.id.value).single().id).isEqualTo(req.id)
    }

    @Test
    fun `rejection leaves the objects as they were, and a cancelled request unlocks them`() {
        val p = product()
        val a = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Pin"))
        val rejected =
            promotions.create(
                alice,
                CreatePromotion("Pin release", "RELEASED", listOf(a.id.value), templateId = review),
            )
        answer(rejected.id, "REJECT")
        assertThat(decided(rejected.id)).isEqualTo(PromotionStatus.REJECTED)
        assertThat(versioning.get(alice, a.id).latest.state).isEqualTo("IN_WORK")

        val cancelled =
            promotions.create(
                alice,
                CreatePromotion("Pin, again", "RELEASED", listOf(a.id.value), templateId = review),
            )
        promotions.cancel(alice, cancelled.id)
        versioning.checkOut(alice, a.id)
        assertThat(versioning.get(alice, a.id).checkedOutBy).isEqualTo(alice.principal.value)
    }
}
