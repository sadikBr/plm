package com.plm.cad.domain

import com.plm.cad.api.CadKind
import com.plm.cad.api.ModelPmi
import com.plm.cad.api.PmiService
import com.plm.cad.api.PmiView
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private const val MAX_VIEWS = 50
private const val MAX_ANNOTATIONS = 2000
private const val MAX_TEXT = 500

/** The sidecar file's form: `{"views": [...]}`. */
internal data class PmiFile(
    val views: List<PmiView> = emptyList(),
)

@Service
@Transactional
internal class DefaultPmiService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val clock: Clock,
) : PmiService {
    internal val json: JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    private fun bad(message: String): Nothing = throw ValidationException("INVALID_PMI", message)

    private fun model(
        actor: Actor,
        modelId: UUID,
    ) = versioning.get(actor, MasterId(modelId)).also {
        val kind = cadKind(types, actor, it.typeId)
        if (kind != CadKind.PART && kind != CadKind.ASSEMBLY) {
            throw ValidationException("NOT_A_MODEL", "Only CAD parts and assemblies carry PMI")
        }
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        modelId: UUID,
        workingCopy: Boolean,
    ): ModelPmi {
        val o = model(actor, modelId)
        val it = if (workingCopy) versioning.workingCopy(actor, o.id) else o.latest
        return read(modelId, it.id.value, it.version)
    }

    private fun read(
        modelId: UUID,
        iteration: UUID,
        version: String,
    ): ModelPmi =
        jdbc
            .sql("select views, source, updated_at, updated_by from cad_pmi where iteration_id = :i")
            .param("i", iteration)
            .query { rs, _ ->
                ModelPmi(
                    modelId,
                    version,
                    json.readValue(rs.getString("views"), Array<PmiView>::class.java).toList(),
                    rs.getString("source"),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("updated_by"),
                )
            }.optional()
            .orElse(ModelPmi(modelId, version, emptyList(), null, null, null))

    /** Checks [views] make sense: ids, texts, points. */
    internal fun check(views: List<PmiView>) {
        if (views.size > MAX_VIEWS) bad("At most $MAX_VIEWS views")
        if (views.sumOf { it.annotations.size } > MAX_ANNOTATIONS) bad("At most $MAX_ANNOTATIONS annotations")

        fun point(
            p: List<Double>?,
            what: String,
        ) {
            if (p != null && (p.size != 3 || p.any { !it.isFinite() })) bad("$what is three finite numbers")
        }
        views.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            bad(
                "Two views have the id '$it'",
            )
        }
        for (v in views) {
            if (v.id.isBlank() || v.name.isBlank()) bad("A view has an id and a name")
            point(v.camera?.position, "A camera's position")
            point(v.camera?.target, "A camera's target")
            for (a in v.annotations) {
                if (a.id.isBlank()) bad("An annotation has an id")
                if (a.text.isBlank() ||
                    a.text.length > MAX_TEXT
                ) {
                    bad("An annotation's text is 1 to $MAX_TEXT characters")
                }
                point(a.at, "An annotation's point")
                point(a.label, "An annotation's label position")
            }
        }
    }

    override fun put(
        actor: Actor,
        modelId: UUID,
        views: List<PmiView>,
        source: String?,
    ): ModelPmi {
        val o = model(actor, modelId)
        val wc = versioning.workingCopy(actor, o.id)
        check(views)
        store(actor.tenant.value, wc.id.value, views, source, actor.principal.value)
        return read(modelId, wc.id.value, wc.version)
    }

    internal fun store(
        tenant: UUID,
        iteration: UUID,
        views: List<PmiView>,
        source: String?,
        by: String,
    ) {
        jdbc
            .sql(
                """
                insert into cad_pmi (iteration_id, tenant_id, views, source, updated_at, updated_by)
                values (:i, :t, cast(:views as jsonb), :source, :now, :by)
                on conflict (iteration_id) do update set views = excluded.views, source = excluded.source,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("i", iteration)
            .param("t", tenant)
            .param("views", json.writeValueAsString(views))
            .param("source", source)
            .param("now", Timestamp.from(clock.instant()))
            .param("by", by)
            .update()
    }
}

/**
 * PMI comes with the model (roadmap 6.12): a `*.pmi.json` file uploaded to a CAD model's iteration
 * is read when it's checked in. A file that doesn't read is left alone, with a warning in the log.
 */
@Component
internal class PmiFromSidecar(
    private val pmi: DefaultPmiService,
    private val content: ContentService,
    private val types: TypeService,
    private val jdbc: JdbcClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "versioning.ObjectCheckedIn.v1") return
        val system = Actor(TenantId(envelope.tenantId), PrincipalId("system"), siteAdmin = true)
        val typeId = TypeId(UUID.fromString(envelope.payload["typeId"].toString()))
        val kind = cadKind(types, system, typeId)
        if (kind != CadKind.PART && kind != CadKind.ASSEMBLY) return
        val iteration = UUID.fromString(envelope.payload["iterationId"].toString())
        val sidecar =
            jdbc
                .sql(
                    """
                    select id, file_name from content_item where iteration_id = :i and copied_from is null
                    and lower(file_name) like '%.pmi.json' order by added_at desc limit 1
                    """.trimIndent(),
                ).param("i", iteration)
                .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("file_name") }
                .optional()
                .orElse(null) ?: return
        try {
            val file = content.open(system, sidecar.first).use { pmi.json.readValue(it, PmiFile::class.java) }
            pmi.check(file.views)
            pmi.store(envelope.tenantId, iteration, file.views, sidecar.second, "system")
        } catch (e: Exception) {
            log.warn("PMI file {} on iteration {} wasn't read: {}", sidecar.second, iteration, e.message)
        }
    }
}
