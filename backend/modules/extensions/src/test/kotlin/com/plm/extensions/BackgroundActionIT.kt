package com.plm.extensions

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.extensions.api.ActionInvocationService
import com.plm.extensions.api.ExtensionService
import com.plm.extensions.infra.web.ActionProgress
import com.plm.extensions.infra.web.BackgroundActionController
import com.plm.jobs.api.Job
import com.plm.jobs.api.JobService
import com.plm.jobs.api.JobStatus
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

/** Roadmap 3.11: a long-running action runs as a job, reports progress and answers later. */
@PlmIntegrationTest
internal class BackgroundActionIT(
    @Autowired private val extensions: ExtensionService,
    @Autowired private val invocations: ActionInvocationService,
    @Autowired private val callbacks: BackgroundActionController,
    @Autowired private val jobs: JobService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val json = JsonMapper.builder().build()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var erp: HttpServer
    private lateinit var id: String

    /** Accepts at once, then reports two steps of progress and posts its answer, as the sample's mock does. */
    @BeforeEach
    fun startErp() {
        erp = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        erp.createContext("/") { ex ->
            val call = json.readValue(ex.requestBody.readAllBytes(), Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val job = call["job"] as Map<String, String>
            val jobId = UUID.fromString(job["id"])
            val token = job["token"]
            val fail = ex.requestURI.query?.contains("fail") == true
            val reply =
                if (fail) {
                    """{"message":"The ERP is busy"}""".toByteArray()
                } else {
                    """{"message":"Accepted"}"""
                        .toByteArray()
                }
            ex.sendResponseHeaders(if (fail) 503 else 202, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
            if (!fail) {
                worker.submit {
                    Thread.sleep(200)
                    callbacks.progress(jobId, token, ActionProgress(1, 2, "Costing at P100"))
                    // A wrong token is refused.
                    runCatching { callbacks.progress(jobId, "pjt_wrong", ActionProgress(2, 2)) }
                    callbacks.progress(jobId, token, ActionProgress(2, 2, "Costing at P200"))
                    val csv = Base64.getEncoder().encodeToString("plant,total\nP100,48.80\n".toByteArray())
                    callbacks.complete(
                        jobId,
                        token,
                        """{"message":"Cost roll-up done","download":{"filename":"rollup.csv","contentType":"text/csv","content":"$csv"}}"""
                            .toByteArray(),
                    )
                }
            }
        }
        erp.start()
        id = "acme.erp-${UUID.randomUUID().toString().take(8)}"
    }

    @AfterEach
    fun stop() {
        erp.stop(0)
        worker.shutdownNow()
    }

    private fun install(query: String = "") {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "extensions/samples").isDirectory) dir = dir.parentFile
        val manifest =
            File(dir, "extensions/samples/erp-sync/plm-extension-2.1.yaml")
                .readText()
                .replace("id: acme.erp-sync", "id: $id")
                .replace("\${config.erpApiUrl}/rollup", "\${config.erpApiUrl}/rollup$query")
        extensions.install(
            admin,
            manifest,
            mapOf(
                "erpUrl" to "https://erp.acme.example",
                "erpApiUrl" to "http://127.0.0.1:${erp.address.port}",
                "apiKey" to "demo-key",
            ),
        )
    }

    private fun part() =
        contexts
            .create(admin, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Bg ${UUID.randomUUID()}"))
            .let { org -> contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Arm")).id }
            .also { teams.put(admin, it, alice.principal, TeamRole.MEMBER) }
            .let { versioning.create(alice, CreateVersionedObject(TypeId.PART, it, "Elbow")) }

    private fun finished(jobId: UUID): Job {
        repeat(150) {
            val job = jobs.get(alice, jobId)
            if (job.status != JobStatus.QUEUED && job.status != JobStatus.RUNNING) return job
            Thread.sleep(100)
        }
        error("Job $jobId didn't finish")
    }

    @Test
    fun `a background action returns a job at once, which ends with the service's later answer`() {
        install()
        val part = part()
        val started = invocations.invoke(alice, part.id, "$id.cost-rollup", mapOf("plants" to "P100"))
        assertThat(started.ok).isTrue()
        val jobId = started.jobId!!
        val job = finished(jobId)
        assertThat(job.status).isEqualTo(JobStatus.SUCCEEDED)
        assertThat(job.submittedBy).isEqualTo(alice.principal.value)
        assertThat(job.title).isEqualTo("Cost roll-up: ${part.number} Elbow")
        assertThat(job.progress?.message).isEqualTo("Costing at P200")
        assertThat(job.result?.get("message")).isEqualTo("Cost roll-up done")
        assertThat(job.result?.get("link")).isEqualTo("/objects/${part.id.value}")

        @Suppress("UNCHECKED_CAST")
        assertThat((job.result?.get("download") as Map<String, Any?>)["filename"]).isEqualTo("rollup.csv")
        assertThat(
            jobs.log(alice, jobId).map {
                it.message
            },
        ).contains("Costing at P100", "The service accepted it and works on it")
        assertThat(invocations.invocations(admin, id).first().status).isEqualTo("OK")
        // Its callbacks stop working once it's over.
        assertThatThrownBy {
            callbacks.progress(
                jobId,
                "pjt_any",
                ActionProgress(1),
            )
        }.isInstanceOf(PlmException::class.java)
    }

    @Test
    fun `a background action the service refuses fails its job with the service's message`() {
        install("?fail")
        val started = invocations.invoke(alice, part().id, "$id.cost-rollup", emptyMap())
        val job = finished(started.jobId!!)
        assertThat(job.status).isEqualTo(JobStatus.FAILED)
        assertThat(job.error).isEqualTo("The ERP is busy")
    }
}
