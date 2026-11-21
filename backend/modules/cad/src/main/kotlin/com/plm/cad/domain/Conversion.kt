package com.plm.cad.domain

import com.plm.cad.api.ConversionService
import com.plm.cad.api.ConversionStatus
import com.plm.cad.api.FamilyService
import com.plm.cad.api.Viewable
import com.plm.content.api.ContentItem
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import com.plm.jobs.api.JobService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(DefaultConversionService::class.java)
internal const val VIEWABLE = "viewable.glb"
internal const val CONVERT_JOB = "cad.convert"

internal fun isStep(fileName: String) =
    fileName.lowercase().let {
        it.endsWith(".stp") ||
            it.endsWith(".step") ||
            it.endsWith(".p21")
    }

@Service
@Transactional
internal class DefaultConversionService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val content: ContentService,
    private val families: FamilyService,
    private val jobs: JobService,
    private val types: TypeService,
    private val clock: Clock,
) : ConversionService {
    private data class Row(
        val status: ConversionStatus,
        val contentId: UUID,
        val error: String?,
        val jobId: UUID?,
        val meshes: Int?,
        val triangles: Int?,
        val updatedAt: java.time.Instant,
    )

    private fun row(iteration: UUID): Row? =
        jdbc
            .sql("select * from cad_conversion where iteration_id = :i")
            .param("i", iteration)
            .query { rs, _ ->
                Row(
                    ConversionStatus.valueOf(rs.getString("status")),
                    rs.getObject("content_id", UUID::class.java),
                    rs.getString("error"),
                    rs.getObject("job_id", UUID::class.java),
                    rs.getObject("meshes") as Int?,
                    rs.getObject("triangles") as Int?,
                    rs.getTimestamp("updated_at").toInstant(),
                )
            }.optional()
            .orElse(null)

    /** The STEP file of an iteration, if it has one. */
    internal fun modelFile(
        actor: Actor,
        id: UUID,
        iteration: UUID,
    ): ContentItem? =
        content.list(actor, MasterId(id), iteration).firstOrNull {
            it.role == ContentRole.PRIMARY &&
                isStep(it.fileName)
        }

    /** Queues the conversion of [iteration]'s file, unless its viewable exists already. */
    internal fun queue(
        actor: Actor,
        id: UUID,
        iteration: UUID,
        file: ContentItem,
        force: Boolean = false,
    ) {
        val ready = !force && content.derived(actor, file.id, VIEWABLE) != null
        val job =
            if (ready) {
                null
            } else {
                jobs.enqueueSystem(
                    actor,
                    CONVERT_JOB,
                    mapOf(
                        "iterationId" to iteration.toString(),
                        "contentId" to file.id.toString(),
                        "objectId" to id.toString(),
                    ),
                    "Convert ${file.fileName}",
                )
            }
        jdbc
            .sql(
                """
                insert into cad_conversion (iteration_id, tenant_id, master_id, content_id, status, job_id, updated_at)
                values (:i, :t, :m, :c, :s, :j, :at)
                on conflict (iteration_id) do update set content_id = excluded.content_id, status = excluded.status,
                    job_id = excluded.job_id, error = null, updated_at = excluded.updated_at
                """.trimIndent(),
            ).param("i", iteration)
            .param("t", actor.tenant.value)
            .param("m", id)
            .param("c", file.id)
            .param("s", if (ready) "READY" else "QUEUED")
            .param("j", job?.id)
            .param("at", Timestamp.from(clock.instant()))
            .update()
    }

    internal fun mark(
        iteration: UUID,
        status: ConversionStatus,
        error: String? = null,
        meshes: Int? = null,
        triangles: Int? = null,
    ) {
        jdbc
            .sql(
                """
                update cad_conversion set status = :s, error = :e, meshes = coalesce(:m, meshes), triangles = coalesce(:tr, triangles),
                    updated_at = :at where iteration_id = :i
                """.trimIndent(),
            ).param("s", status.name)
            .param("e", error)
            .param("m", meshes)
            .param("tr", triangles)
            .param("at", Timestamp.from(clock.instant()))
            .param("i", iteration)
            .update()
    }

    /** The model whose file is shown for [id]: itself, or its family's generic. */
    private fun source(
        actor: Actor,
        id: UUID,
    ): UUID {
        val membership = families.membership(actor, id)
        return if (membership.role == "INSTANCE") membership.table!!.generic.id else id
    }

    @Transactional(readOnly = true)
    override fun viewable(
        actor: Actor,
        id: UUID,
    ): Viewable {
        val modelId = source(actor, id)
        val o = versioning.get(actor, MasterId(modelId))
        val file = modelFile(actor, modelId, o.latest.id.value)
        val row = row(o.latest.id.value)
        val ready = file?.let { content.derived(actor, it.id, VIEWABLE) } != null
        val status =
            when {
                file == null -> ConversionStatus.NONE
                ready -> ConversionStatus.READY
                row == null -> ConversionStatus.NONE
                else -> row.status
            }
        val url =
            if (ready) {
                content
                    .derivedLink(
                        actor,
                        file.id,
                        VIEWABLE,
                        file.fileName.substringBeforeLast('.') + ".glb",
                    ).url
            } else {
                null
            }
        return Viewable(
            status,
            modelId,
            o.latest.version,
            file?.fileName,
            url,
            row?.error,
            row?.jobId,
            row?.meshes,
            row?.triangles,
            row?.updatedAt,
        )
    }

    override fun convert(
        actor: Actor,
        id: UUID,
    ): Viewable {
        val modelId = source(actor, id)
        val o = versioning.get(actor, MasterId(modelId))
        val file =
            modelFile(actor, modelId, o.latest.id.value)
                ?: throw ConflictException("NO_MODEL_FILE", "${o.number} has no STEP file to convert")
        queue(actor, modelId, o.latest.id.value, file, force = true)
        return viewable(actor, id)
    }
}

/** A CAD document checked in with a STEP file gets converted (roadmap 6.6). */
@Component
internal class ConvertOnCheckIn(
    private val conversions: DefaultConversionService,
    private val types: TypeService,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "versioning.ObjectCheckedIn.v1") return
        val system = Actor(TenantId(envelope.tenantId), PrincipalId("system"), siteAdmin = true)
        val typeId = TypeId(UUID.fromString(envelope.payload["typeId"].toString()))
        if (TypeId.CAD_DOCUMENT !in types.lineage(system, typeId)) return
        val id = UUID.fromString(envelope.payload["objectId"].toString())
        val iteration = UUID.fromString(envelope.payload["iterationId"].toString())
        val file = conversions.modelFile(system, id, iteration) ?: return
        conversions.queue(system, id, iteration, file)
    }
}

/** Runs the converter on a STEP file and keeps the .glb (roadmap 6.6). */
@Component
internal class ConvertJob(
    private val conversions: DefaultConversionService,
    private val content: ContentService,
    @param:Value("\${plm.cad.converter.node:node}") private val node: String,
    @param:Value("\${plm.cad.converter.script:}") private val script: String,
    @param:Value("\${plm.cad.converter.timeout-seconds:300}") private val timeoutSeconds: Long,
) : JobHandler {
    override val type = CONVERT_JOB
    override val label = "Convert a CAD model"
    override val description = "Converts a CAD document's STEP file to glTF for the web viewer."
    override val maxAttempts = 2
    private val json = JsonMapper.builder().build()

    /** The converter script: configured, or found from the working directory up. */
    internal fun converter(): Path? {
        if (script.isNotBlank()) return Path.of(script).takeIf { Files.exists(it) }
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("workers/cad-converter/convert.mjs")
            if (Files.exists(candidate) &&
                Files.isDirectory(dir.resolve("workers/cad-converter/node_modules"))
            ) {
                return candidate
            }
            dir = dir.parent
        }
        return null
    }

    override fun run(context: JobContext): Map<String, Any?> {
        val iteration = UUID.fromString(context.payload["iterationId"].toString())
        val contentId = UUID.fromString(context.payload["contentId"].toString())
        conversions.mark(iteration, ConversionStatus.RUNNING)
        val tool = converter()
        if (tool == null) {
            conversions.mark(
                iteration,
                ConversionStatus.FAILED,
                "The converter isn't installed (workers/cad-converter: npm ci)",
            )
            throw JobPermanentFailure("The converter isn't installed (workers/cad-converter: npm ci)")
        }
        val dir = Files.createTempDirectory("plm-convert")
        try {
            val input = dir.resolve("model.step")
            val output = dir.resolve("model.glb")
            content.open(context.actor, contentId).use { Files.copy(it, input) }
            context.progress(1, 3, "Converting")
            val outFile = dir.resolve("stdout.txt").toFile()
            val errFile = dir.resolve("stderr.txt").toFile()
            val process =
                ProcessBuilder(node, tool.toString(), input.toString(), output.toString())
                    .directory(tool.parent.toFile())
                    .redirectOutput(outFile)
                    .redirectError(errFile)
                    .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                conversions.mark(iteration, ConversionStatus.FAILED, "It took longer than $timeoutSeconds s")
                throw JobPermanentFailure("Conversion took longer than $timeoutSeconds s")
            }
            val stdout = outFile.readText()
            val stderr = errFile.readText()
            if (process.exitValue() != 0 || !Files.exists(output)) {
                val why = stderr.lines().lastOrNull { it.isNotBlank() } ?: "exit ${process.exitValue()}"
                conversions.mark(iteration, ConversionStatus.FAILED, why.take(500))
                throw JobPermanentFailure("Conversion failed: $why")
            }
            context.progress(2, 3, "Storing")
            content.putDerived(contentId, VIEWABLE, "model/gltf-binary", output)
            @Suppress("UNCHECKED_CAST")
            val summary =
                runCatching {
                    json.readValue(
                        stdout.lines().last {
                            it.startsWith("{")
                        },
                        Map::class.java,
                    ) as Map<String, Any?>
                }.getOrDefault(emptyMap())
            conversions.mark(
                iteration,
                ConversionStatus.READY,
                null,
                (summary["meshes"] as? Number)?.toInt(),
                (summary["triangles"] as? Number)?.toInt(),
            )
            context.log("Converted: $summary")
            log.info("Converted {}: {}", contentId, summary)
            return summary
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
