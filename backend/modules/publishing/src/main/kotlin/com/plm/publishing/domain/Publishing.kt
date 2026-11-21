package com.plm.publishing.domain

import com.plm.bom.api.BomService
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.identity.api.PrincipalDirectory
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.publishing.api.PdfFile
import com.plm.publishing.api.Publication
import com.plm.publishing.api.PublishRule
import com.plm.publishing.api.PublishRuleSettings
import com.plm.publishing.api.PublishSettings
import com.plm.publishing.api.PublishTrigger
import com.plm.publishing.api.PublishingService
import com.plm.types.api.AttributeService
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = LoggerFactory.getLogger(DefaultPublishingService::class.java)
internal const val PUBLISH_JOB = "publishing.publish"
private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

private fun stateName(s: String) =
    s
        .lowercase()
        .split('_')
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

@Service
@Transactional
internal class DefaultPublishingService(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val attributes: AttributeService,
    private val content: ContentService,
    private val bom: BomService,
    private val jobs: JobService,
    private val directory: PrincipalDirectory,
    private val clock: Clock,
) : PublishingService {
    private val json = JsonMapper.builder().build()

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators manage publishing")
    }

    private val ruleMapper =
        org.springframework.jdbc.core.RowMapper { rs, _ ->
            PublishRule(
                rs.getObject("id", UUID::class.java),
                rs.getString("name"),
                json.readValue(rs.getString("types"), Array<String>::class.java).toList(),
                PublishTrigger.valueOf(rs.getString("trigger")),
                rs.getString("state"),
                rs.getBoolean("watermark"),
                rs.getBoolean("active"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by"),
            )
        }

    internal fun allRules(tenant: TenantId): List<PublishRule> =
        jdbc
            .sql("select * from publish_rule where tenant_id = :t order by name")
            .param("t", tenant.value)
            .query(ruleMapper)
            .list()

    @Transactional(readOnly = true)
    override fun rules(actor: Actor): List<PublishRule> = allRules(actor.tenant)

    override fun saveRule(
        actor: Actor,
        id: UUID?,
        settings: PublishRuleSettings,
    ): PublishRule {
        requireAdmin(actor)

        fun bad(m: String): Nothing = throw ValidationException("INVALID_RULE", m)
        if (settings.name.isBlank()) bad("Name it")
        if (settings.trigger == PublishTrigger.STATE && settings.state.isNullOrBlank()) bad("Which state publishes?")
        val known = types.list(actor).map { it.name }.toSet()
        settings.types.firstOrNull { it !in known }?.let { bad("Unknown type $it") }
        val rid = id ?: UuidV7.generate(clock)
        if (id != null &&
            allRules(actor.tenant).none { it.id == id }
        ) {
            throw NotFoundException("RULE_NOT_FOUND", "No such rule")
        }
        jdbc
            .sql(
                """
                insert into publish_rule (id, tenant_id, name, types, trigger, state, watermark, active, updated_at, updated_by)
                values (:id, :t, :name, cast(:types as jsonb), :trigger, :state, :watermark, :active, :at, :by)
                on conflict (id) do update set name = excluded.name, types = excluded.types, trigger = excluded.trigger,
                    state = excluded.state, watermark = excluded.watermark, active = excluded.active,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("id", rid)
            .param("t", actor.tenant.value)
            .param("name", settings.name.trim())
            .param("types", json.writeValueAsString(settings.types))
            .param("trigger", settings.trigger.name)
            .param("state", if (settings.trigger == PublishTrigger.STATE) settings.state else null)
            .param("watermark", settings.watermark)
            .param("active", settings.active)
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return allRules(actor.tenant).first { it.id == rid }
    }

    override fun deleteRule(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        if (jdbc
                .sql(
                    "delete from publish_rule where id = :id and tenant_id = :t",
                ).param("id", id)
                .param("t", actor.tenant.value)
                .update() ==
            0
        ) {
            throw NotFoundException("RULE_NOT_FOUND", "No such rule")
        }
    }

    @Transactional(readOnly = true)
    override fun settings(actor: Actor): PublishSettings =
        jdbc
            .sql("select watermark_downloads from publish_settings where tenant_id = :t")
            .param("t", actor.tenant.value)
            .query { rs, _ -> PublishSettings(rs.getBoolean(1)) }
            .optional()
            .orElse(PublishSettings(true))

    override fun saveSettings(
        actor: Actor,
        settings: PublishSettings,
    ): PublishSettings {
        requireAdmin(actor)
        jdbc
            .sql(
                """
                insert into publish_settings (tenant_id, watermark_downloads, updated_at, updated_by) values (:t, :w, :at, :by)
                on conflict (tenant_id) do update set watermark_downloads = excluded.watermark_downloads,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("t", actor.tenant.value)
            .param("w", settings.watermarkDownloads)
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return settings(actor)
    }

    private val publicationMapper =
        org.springframework.jdbc.core.RowMapper { rs, _ ->
            Publication(
                rs.getObject("id", UUID::class.java),
                rs.getObject("master_id", UUID::class.java),
                rs.getString("version"),
                rs.getString("state"),
                rs.getString("rule_name"),
                rs.getString("status"),
                rs.getString("source"),
                rs.getString("file_name"),
                rs.getObject("pages") as Int?,
                rs.getString("error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"),
            )
        }

    private val selectPublications =
        """
        select p.id, p.master_id, p.status, p.source, p.file_name, p.pages, p.error, p.created_at, p.created_by,
               i.revision || '.' || i.iteration as version, i.state, r.name as rule_name
        from publication p join object_iteration i on i.id = p.iteration_id left join publish_rule r on r.id = p.rule_id
        """.trimIndent()

    @Transactional(readOnly = true)
    override fun publications(
        actor: Actor,
        objectId: UUID,
    ): List<Publication> {
        versioning.get(actor, MasterId(objectId))
        return jdbc
            .sql("$selectPublications where p.master_id = :m order by p.created_at desc limit 50")
            .param("m", objectId)
            .query(publicationMapper)
            .list()
    }

    /** Queues a publication of [iteration] (made by the job). */
    internal fun queue(
        actor: Actor,
        objectId: UUID,
        iteration: UUID,
        ruleId: UUID?,
        by: String,
        title: String,
    ): UUID {
        val id = UuidV7.generate(clock)
        jdbc
            .sql(
                """
                insert into publication (id, tenant_id, master_id, iteration_id, rule_id, status, created_at, created_by)
                values (:id, :t, :m, :i, :r, 'QUEUED', :at, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("t", actor.tenant.value)
            .param("m", objectId)
            .param("i", iteration)
            .param("r", ruleId)
            .param("at", Timestamp.from(clock.instant()))
            .param("by", by)
            .update()
        jobs.enqueueSystem(actor, PUBLISH_JOB, mapOf("publicationId" to id.toString()), "Publish $title")
        return id
    }

    override fun publish(
        actor: Actor,
        objectId: UUID,
    ): Publication {
        val o = versioning.get(actor, MasterId(objectId))
        val id =
            queue(actor, objectId, o.latest.id.value, null, actor.principal.value, "${o.number} ${o.latest.version}")
        return publications(actor, objectId).first { it.id == id }
    }

    private fun name(
        tenant: TenantId,
        principal: String,
    ): String = directory.displayName(tenant, PrincipalId(principal))

    /** Stamps [pdf] for the person downloading it, when the settings say so. */
    private fun forDownload(
        actor: Actor,
        pdf: ByteArray,
        what: String,
    ): ByteArray {
        if (!settings(actor).watermarkDownloads) return pdf
        val who = name(actor.tenant, actor.principal.value)
        return Pdfs
            .stamp(
                pdf,
                "Downloaded by $who on ${DAY.format(clock.instant())} - $what - uncontrolled when printed",
                null,
                top = true,
            ).first
    }

    @Transactional(readOnly = true)
    override fun download(
        actor: Actor,
        publicationId: UUID,
    ): PdfFile {
        val (row, pdf) =
            jdbc
                .sql(
                    selectPublications.replace("select p.id,", "select p.pdf, p.id,") +
                        " where p.id = :id and p.tenant_id = :t",
                ).param("id", publicationId)
                .param("t", actor.tenant.value)
                .query { rs, n -> publicationMapper.mapRow(rs, n) to rs.getBytes("pdf") }
                .optional()
                .orElse(null) ?: throw NotFoundException("PUBLICATION_NOT_FOUND", "No such publication")
        val o = versioning.get(actor, MasterId(row.objectId))
        if (pdf == null) throw NotFoundException("PUBLICATION_NOT_FOUND", "It isn't ready")
        return PdfFile(
            row.fileName ?: "${o.number}.pdf",
            forDownload(actor, pdf, "${o.number} ${row.version} ${stateName(row.state)}"),
        )
    }

    @Transactional(readOnly = true)
    override fun datasheet(
        actor: Actor,
        objectId: UUID,
    ): PdfFile {
        val o = versioning.get(actor, MasterId(objectId))
        val (pdf, _) = Pdfs.datasheet(sheet(actor, o, o.latest))
        return PdfFile(
            "${o.number}-${o.latest.version}-data-sheet.pdf",
            forDownload(actor, pdf, "${o.number} ${o.latest.version} ${stateName(o.latest.state)}"),
        )
    }

    @Transactional(readOnly = true)
    override fun watermarked(
        actor: Actor,
        contentId: UUID,
    ): PdfFile {
        val item =
            jdbc
                .sql("select master_id, file_name, iteration_id from content_item where id = :id")
                .param("id", contentId)
                .query {
                    rs,
                    _,
                    ->
                    Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java))
                }.optional()
                .orElse(null) ?: throw NotFoundException("CONTENT_NOT_FOUND", "There's no such file")
        if (!item.second.lowercase().endsWith(
                ".pdf",
            )
        ) {
            throw ValidationException("NOT_A_PDF", "Only PDF files are watermarked")
        }
        val bytes = content.open(actor, contentId).use { it.readBytes() }
        val o = versioning.get(actor, MasterId(item.first))
        val it = versioning.history(actor, o.id).firstOrNull { h -> h.id.value == item.third } ?: o.latest
        return PdfFile(item.second, forDownload(actor, bytes, "${o.number} ${it.version} ${stateName(it.state)}"))
    }

    /** What a data sheet of [o] at [iteration] shows. */
    internal fun sheet(
        actor: Actor,
        o: VersionedObject,
        iteration: Iteration,
    ): DataSheet {
        val type = types.get(actor, o.typeId)
        val defs = attributes.effective(actor, o.typeId)

        fun shown(v: Any?): String =
            when (v) {
                null -> "-"
                is Map<*, *> ->
                    (v["value"] ?: v.values.firstOrNull())?.toString().orEmpty() +
                        (v["unit"]?.let { " $it" } ?: "")
                is Collection<*> -> v.joinToString(", ")
                else -> v.toString()
            }
        val attributeRows =
            defs
                .filter { iteration.attributes[it.name] != null }
                .map { listOf(it.labels["en"] ?: it.name, shown(iteration.attributes[it.name])) }
        val sections =
            mutableListOf(DataSheet.Section("Attributes", listOf("Attribute", "Value"), attributeRows, listOf(1f, 2f)))
        if (TypeId.PART in types.lineage(actor, o.typeId)) {
            val lines = runCatching { bom.usages(actor, o.id, iteration.id.value) }.getOrDefault(emptyList())
            sections +=
                DataSheet.Section(
                    "Structure",
                    listOf("Line", "Number", "Name", "Version", "Quantity", "Unit"),
                    lines.map {
                        listOf(
                            it.lineNumber.toString(),
                            it.child.number ?: "(no access)",
                            it.child.name.orEmpty(),
                            it.child.version.orEmpty(),
                            it.quantity.stripTrailingZeros().toPlainString(),
                            it.unit,
                        )
                    },
                    listOf(0.6f, 1.2f, 2.4f, 0.8f, 0.8f, 0.7f),
                )
        }
        val files = runCatching { content.list(actor, o.id, iteration.id.value) }.getOrDefault(emptyList())
        if (files.isNotEmpty()) {
            sections +=
                DataSheet.Section(
                    "Files",
                    listOf("File", "Role", "Size", "Added"),
                    files.map {
                        listOf(
                            it.fileName,
                            it.role.name.lowercase(),
                            "%,d bytes".format(it.size),
                            DAY.format(it.addedAt),
                        )
                    },
                    listOf(2.6f, 0.8f, 1f, 1.2f),
                )
        }
        val history = versioning.history(actor, o.id).filter { it.checkedInAt != null }.take(12)
        sections +=
            DataSheet.Section(
                "History",
                listOf("Version", "State", "Checked in", "By", "Comment"),
                history.map {
                    listOf(
                        it.version,
                        stateName(it.state),
                        it.checkedInAt?.let(DAY::format).orEmpty(),
                        name(actor.tenant, it.createdBy),
                        it.comment.orEmpty(),
                    )
                },
                listOf(0.6f, 0.9f, 1.3f, 1.1f, 2.4f),
            )
        return DataSheet(
            "${o.number}  ${o.name}",
            "${type.displayName} - version ${iteration.version} - ${stateName(iteration.state)}",
            listOf(
                "Number" to o.number,
                "Name" to o.name,
                "Type" to type.displayName,
                "Version" to iteration.version,
                "State" to stateName(iteration.state),
                "Owner" to name(actor.tenant, o.owner),
                "Created" to "${DAY.format(o.createdAt)} by ${name(actor.tenant, o.createdBy)}",
            ),
            sections,
            "Data sheet of ${o.number} ${iteration.version} - made ${DAY.format(clock.instant())}",
        )
    }

    /** The job: makes a queued publication's PDF. */
    internal fun make(
        system: Actor,
        publicationId: UUID,
    ): Map<String, Any?> {
        data class Queued(
            val master: UUID,
            val iteration: UUID,
            val rule: UUID?,
        )
        val q =
            jdbc
                .sql("select master_id, iteration_id, rule_id from publication where id = :id")
                .param("id", publicationId)
                .query {
                    rs,
                    _,
                    ->
                    Queued(
                        rs.getObject(1, UUID::class.java),
                        rs.getObject(2, UUID::class.java),
                        rs.getObject(3, UUID::class.java),
                    )
                }.optional()
                .orElse(null) ?: return mapOf("skipped" to "gone")
        val o = versioning.get(system, MasterId(q.master))
        val it = versioning.history(system, o.id).first { h -> h.id.value == q.iteration }
        val rule = q.rule?.let { r -> allRules(system.tenant).firstOrNull { it.id == r } }
        val diagonal = if (rule == null || rule.watermark) stateName(it.state).uppercase() else null
        val footer = "${o.number} ${it.version} ${stateName(
            it.state,
        )} - ${o.name} - published ${DAY.format(clock.instant())}"
        val pdfFile =
            content
                .list(system, o.id, it.id.value)
                .firstOrNull { c -> c.role == ContentRole.PRIMARY && c.fileName.lowercase().endsWith(".pdf") }
        val (source, name, made) =
            if (pdfFile != null) {
                val bytes = content.open(system, pdfFile.id).use { s -> s.readBytes() }
                Triple(
                    "FILE",
                    pdfFile.fileName.substringBeforeLast('.') + "-${it.version}-published.pdf",
                    Pdfs.stamp(bytes, footer, diagonal),
                )
            } else {
                val (sheet, _) = Pdfs.datasheet(sheet(system, o, it))
                Triple(
                    "DATASHEET",
                    "${o.number}-${it.version}-data-sheet.pdf",
                    if (diagonal !=
                        null
                    ) {
                        Pdfs.stamp(sheet, footer, diagonal)
                    } else {
                        Pdfs.stamp(sheet, footer, null)
                    },
                )
            }
        jdbc
            .sql(
                "update publication set status = 'READY', source = :s, file_name = :n, pdf = :pdf, pages = :pages, error = null where id = :id",
            ).param("s", source)
            .param("n", name)
            .param("pdf", made.first)
            .param("pages", made.second)
            .param("id", publicationId)
            .update()
        log.info("Published {} {} ({}, {} pages)", o.number, it.version, source, made.second)
        return mapOf("source" to source, "pages" to made.second)
    }

    internal fun fail(
        publicationId: UUID,
        message: String,
    ) {
        jdbc
            .sql(
                "update publication set status = 'FAILED', error = :e where id = :id",
            ).param("e", message.take(500))
            .param("id", publicationId)
            .update()
    }
}

/** Publishes on check-in or on reaching a state, as the rules say (roadmap 6.7). */
@Component
internal class PublishOnEvents(
    private val publishing: DefaultPublishingService,
    private val types: TypeService,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        val trigger =
            when (envelope.eventType) {
                "versioning.ObjectCheckedIn.v1" -> PublishTrigger.CHECK_IN
                "versioning.ObjectStateChanged.v1" -> PublishTrigger.STATE
                else -> return
            }
        val tenant = TenantId(envelope.tenantId)
        val rules = publishing.allRules(tenant).filter { it.active && it.trigger == trigger }
        if (rules.isEmpty()) return
        val system = Actor(tenant, PrincipalId("system"), siteAdmin = true)
        val p = envelope.payload
        val typeId = TypeId(UUID.fromString(p["typeId"].toString()))
        val lineage = types.lineage(system, typeId).map { types.get(system, it).name }
        val matching =
            rules.filter { r ->
                (r.types.isEmpty() || r.types.any { it in lineage }) &&
                    (trigger == PublishTrigger.CHECK_IN || p["to"] == r.state)
            }
        for (r in matching) {
            publishing.queue(
                system,
                UUID.fromString(p["objectId"].toString()),
                UUID.fromString(p["iterationId"].toString()),
                r.id,
                envelope.actor,
                "${p["number"]} ${p["version"]}",
            )
        }
    }
}

@Component
internal class PublishJob(
    private val publishing: DefaultPublishingService,
) : JobHandler {
    override val type = PUBLISH_JOB
    override val label = "Publish a PDF"
    override val description = "Makes a version's published PDF: its PDF file stamped, or a data sheet."
    override val maxAttempts = 2

    override fun run(context: JobContext): Map<String, Any?> {
        val id = UUID.fromString(context.payload["publicationId"].toString())
        return try {
            publishing.make(context.actor, id)
        } catch (e: RuntimeException) {
            publishing.fail(id, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }
}
