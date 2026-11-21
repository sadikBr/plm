package com.plm.bom

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.MassReplace
import com.plm.bom.api.StructureOperationsService
import com.plm.jobs.api.JobService
import com.plm.jobs.api.JobStatus
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** Mass replace across where-used (roadmap 4.14). */
@PlmIntegrationTest
internal class StructureOperationsIT(
    @Autowired private val operations: StructureOperationsService,
    @Autowired private val bom: BomService,
    @Autowired private val jobs: JobService,
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
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Ops ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun part(
        p: ContextId,
        name: String,
    ): MasterId = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id

    private fun use(
        parent: MasterId,
        child: MasterId,
        quantity: Int,
    ) {
        versioning.checkOut(alice, parent)
        bom.add(alice, parent, AddUsage(child.value, BigDecimal(quantity), findNumber = "7"))
        versioning.checkIn(alice, parent)
    }

    @Suppress("UNCHECKED_CAST")
    private fun results(request: MassReplace): Map<String, String> {
        val job = operations.replaceEverywhere(alice, request)
        var tries = 0
        while (jobs.get(alice, job.id).status in setOf(JobStatus.QUEUED, JobStatus.RUNNING) && tries++ < 200) {
            Thread.sleep(100)
        }
        val done = jobs.get(alice, job.id)
        assertThat(done.status).isEqualTo(JobStatus.SUCCEEDED)
        return (done.result!!["results"] as List<Map<String, Any?>>).associate {
            it["number"].toString() to "${it["ok"]}: ${it["message"]}"
        }
    }

    @Test
    fun `a part is replaced in every parent chosen, keeping the lines, and released parents are revised if asked`() {
        val p = product()
        val oldBolt = part(p, "Bolt M8 zinc")
        val newBolt = part(p, "Bolt M8 stainless")
        val (a, b, c, d) =
            listOf(
                part(p, "Bracket A"),
                part(p, "Bracket B"),
                part(p, "Bracket C"),
                part(p, "Bracket D"),
            )
        listOf(a, b, c).forEach { use(it, oldBolt, 4) }
        versioning.setState(admin, c, SetState("RELEASED", override = true))
        val number = { id: MasterId -> versioning.get(alice, id).number }

        val first = results(MassReplace(oldBolt.value, newBolt.value, listOf(a.value, c.value, d.value)))
        assertThat(first[number(a)]).startsWith("true").contains("on 1 line")
        assertThat(first[number(c)]).startsWith("false").contains("RELEASED")
        assertThat(first[number(d)]).startsWith("false").contains("doesn't use")
        val line = bom.usages(alice, a).single()
        assertThat(line.child.id).isEqualTo(newBolt.value)
        assertThat(line.quantity).isEqualByComparingTo("4")
        assertThat(line.findNumber).isEqualTo("7")
        // Nothing of d changed: no check-out left behind.
        assertThat(versioning.get(alice, d).checkedOutBy).isNull()

        val second = results(MassReplace(oldBolt.value, newBolt.value, listOf(b.value, c.value), reviseReleased = true))
        assertThat(second.values).allSatisfy { assertThat(it).startsWith("true") }
        assertThat(second[number(c)]).contains("revised")
        assertThat(versioning.get(alice, c).latest.version).startsWith("B.")
        assertThat(bom.whereUsed(alice, oldBolt).nodes).isEmpty()
    }
}
