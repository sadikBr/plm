package com.plm.bulk

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bulk.api.BulkOperation
import com.plm.bulk.api.BulkRequest
import com.plm.bulk.api.BulkService
import com.plm.jobs.api.JobService
import com.plm.jobs.api.JobStatus
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Mass operations (roadmap 3.5), run by the background worker. */
@PlmIntegrationTest
class BulkIT(
    @Autowired private val bulk: BulkService,
    @Autowired private val jobs: JobService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val organizing: OrganizingService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Bulk ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun parts(
        context: ContextId,
        n: Int,
    ): List<MasterId> =
        (1..n).map {
            versioning
                .create(
                    alice,
                    CreateVersionedObject(
                        TypeId.PART,
                        context,
                        "Part $it",
                        attributes =
                            mapOf(
                                "material" to "Al",
                            ),
                    ),
                ).id
        }

    @Suppress("UNCHECKED_CAST")
    private fun finished(id: UUID): Map<String, Any?> {
        var tries = 0
        while (jobs.get(alice, id).status in setOf(JobStatus.QUEUED, JobStatus.RUNNING) &&
            tries++ < 150
        ) {
            Thread.sleep(100)
        }
        val job = jobs.get(alice, id)
        assertThat(job.status).isEqualTo(JobStatus.SUCCEEDED)
        return job.result!!
    }

    @Test
    fun `a value is changed on many objects, each with its own outcome`() {
        val p = product()
        val ids = parts(p, 3)
        versioning.checkOut(admin, ids[1])
        val job =
            bulk.start(
                alice,
                BulkRequest(
                    BulkOperation.SET_ATTRIBUTE,
                    ids.map {
                        it.value
                    },
                    attribute = "material",
                    value = "Steel",
                ),
            )
        assertThat(job.title).isEqualTo("Set material: 3 objects")
        val result = finished(job.id)
        assertThat(result["succeeded"]).isEqualTo(2)
        assertThat(result["failed"]).isEqualTo(1)
        @Suppress("UNCHECKED_CAST")
        val failed = (result["results"] as List<Map<String, Any?>>).single { it["ok"] == false }
        assertThat(failed["objectId"]).isEqualTo(ids[1].value.toString())
        assertThat(failed["message"].toString()).contains("checked out")
        assertThat(versioning.get(alice, ids[0]).latest.attributes["material"]).isEqualTo("Steel")
        assertThat(versioning.get(alice, ids[0]).latest.version).isEqualTo("A.2")
    }

    @Test
    fun `objects are moved and deleted in bulk, by the same rules as one by one`() {
        val p = product()
        val ids = parts(p, 2)
        val folder = organizing.createFolder(alice, p.value, null, "Batch")
        finished(
            bulk
                .start(
                    alice,
                    BulkRequest(
                        BulkOperation.MOVE,
                        ids.map {
                            it.value
                        },
                        contextId = p.value,
                        folderId = folder.id,
                    ),
                ).id,
        )
        assertThat(ids.map { versioning.get(alice, it).folderId }).containsOnly(folder.id)

        val result = finished(bulk.start(alice, BulkRequest(BulkOperation.DELETE, ids.map { it.value })).id)
        assertThat(result["succeeded"]).isEqualTo(2)
        assertThat(
            versioning.list(
                alice,
                com.plm.versioning.api
                    .ObjectQuery(p),
            ),
        ).isEmpty()
    }

    @Test
    fun `a request is checked before it starts`() {
        val code = { r: BulkRequest -> (runCatching { bulk.start(alice, r) }.exceptionOrNull() as PlmException).code }
        assertThat(code(BulkRequest(BulkOperation.DELETE, emptyList()))).isEqualTo("INVALID_BULK_REQUEST")
        assertThat(
            code(BulkRequest(BulkOperation.SET_STATE, listOf(UUID.randomUUID()))),
        ).isEqualTo("INVALID_BULK_REQUEST")
    }
}
