package com.plm.office.domain

import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.identity.api.PrincipalDirectory
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import com.plm.jobs.api.JobService
import com.plm.office.api.OfficeFile
import com.plm.office.api.OfficeService
import com.plm.office.api.PropertyMapping
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeService
import com.plm.types.api.DataType
import com.plm.types.api.EnumerationService
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.IterationStatus
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** LibreOffice, if installed: it turns Office files into PDFs for previews (roadmap 8.4). */
@Component
internal class OfficeConverter(
    @Value("\${plm.office.soffice:soffice}") private val command: String,
) {
    val available: Boolean by lazy {
        val c = File(command)
        if (c.isAbsolute) {
            c.canExecute()
        } else {
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .any { File(it, command).canExecute() }
        }
    }

    /** The PDF of [input], or null when the conversion fails. */
    fun toPdf(input: java.nio.file.Path): java.nio.file.Path? {
        val out = Files.createTempDirectory("plm-office-out-")
        // Its own profile, so conversions never clash with a LibreOffice the user has open.
        val profile = Files.createTempDirectory("plm-office-profile-")
        val process =
            ProcessBuilder(
                command,
                "-env:UserInstallation=${profile.toUri()}",
                "--headless",
                "--norestore",
                "--convert-to",
                "pdf",
                "--outdir",
                out.toString(),
                input.toString(),
            ).redirectErrorStream(true)
                .start()
        process.inputStream.readAllBytes()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        profile.toFile().deleteRecursively()
        return Files.list(out).use { files -> files.filter { it.toString().endsWith(".pdf") }.findFirst().orElse(null) }
    }
}

@Service
@Transactional
internal class DefaultOfficeService(
    private val jdbc: JdbcClient,
    private val types: TypeService,
    private val attributes: AttributeService,
    private val enumerations: EnumerationService,
    private val versioning: VersioningService,
    private val content: ContentService,
    private val directory: PrincipalDirectory,
    private val jobs: JobService,
    private val converter: OfficeConverter,
) : OfficeService {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun mappings(
        actor: Actor,
        typeId: UUID,
    ): List<PropertyMapping> {
        val lineage = types.lineage(actor, TypeId(typeId)).map { it.value }
        val rows =
            jdbc
                .sql(
                    "select type_id, property, attribute from office_property_map where tenant_id = :t and type_id in (:types)",
                ).param("t", actor.tenant.value)
                .param("types", lineage)
                .query {
                    rs,
                    _,
                    ->
                    Triple(
                        rs.getObject("type_id", UUID::class.java),
                        rs.getString("property"),
                        rs.getString("attribute"),
                    )
                }.list()
        // Nearest type wins: walk from the type itself up to the root.
        val out = linkedMapOf<String, PropertyMapping>()
        for (t in lineage.reversed()) {
            rows.filter { it.first == t }.sortedBy { it.second.lowercase() }.forEach { (_, property, attribute) ->
                out.putIfAbsent(property.lowercase(), PropertyMapping(property, attribute, inherited = t != typeId))
            }
        }
        return out.values.sortedWith(compareBy({ it.inherited }, { it.property.lowercase() }))
    }

    override fun setMappings(
        actor: Actor,
        typeId: UUID,
        mappings: List<PropertyMapping>,
    ): List<PropertyMapping> {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators map Office properties",
            )
        }
        val defs = attributes.effective(actor, TypeId(typeId)).associateBy { it.name }
        val own = mappings.filterNot { it.inherited }
        for (m in own) {
            val p = m.property.trim()
            if (p.isEmpty() ||
                p.length > 100
            ) {
                throw ValidationException("INVALID_PROPERTY", "Name each property (up to 100 characters)")
            }
            if (m.attribute !in
                defs
            ) {
                throw ValidationException("UNKNOWN_ATTRIBUTE", "The type has no attribute “${m.attribute}”")
            }
        }
        jdbc
            .sql("delete from office_property_map where tenant_id = :t and type_id = :type")
            .param("t", actor.tenant.value)
            .param("type", typeId)
            .update()
        for (m in own.distinctBy { it.property.trim().lowercase() }) {
            jdbc
                .sql(
                    "insert into office_property_map (tenant_id, type_id, property, attribute) values (:t, :type, :p, :a)",
                ).param("t", actor.tenant.value)
                .param("type", typeId)
                .param("p", m.property.trim())
                .param("a", m.attribute)
                .update()
        }
        return mappings(actor, typeId)
    }

    @Transactional(readOnly = true)
    override fun properties(
        actor: Actor,
        contentId: UUID,
    ): Map<String, String> {
        val item = content.item(actor, contentId)
        if (!OfficeProperties.isOffice(
                item.fileName,
            )
        ) {
            throw ValidationException("NOT_AN_OFFICE_FILE", "${item.fileName} isn't an Office file")
        }
        return OfficeProperties.read(content.open(actor, contentId).use { it.readAllBytes() })
    }

    @Transactional(readOnly = true)
    override fun download(
        actor: Actor,
        contentId: UUID,
    ): OfficeFile {
        val item = content.item(actor, contentId)
        val bytes = content.open(actor, contentId).use { it.readAllBytes() }
        if (!OfficeProperties.isOffice(item.fileName)) return OfficeFile(item.fileName, item.contentType, bytes)
        val obj = versioning.get(actor, MasterId(item.masterId))
        val iteration =
            if (obj.checkedOutBy == actor.principal.value) {
                versioning.workingCopy(actor, obj.id).takeIf { it.id.value == item.iterationId }
            } else {
                null
            } ?: versioning.history(actor, obj.id).firstOrNull { it.id.value == item.iterationId } ?: obj.latest
        val defs = attributes.effective(actor, obj.typeId).associateBy { it.name }
        val values =
            mappings(actor, obj.typeId.value)
                .mapNotNull { m ->
                    val v = iteration.attributes[m.attribute] ?: return@mapNotNull null
                    display(actor, defs[m.attribute], v)?.let { m.property to it }
                }.toMap()
        return OfficeFile(item.fileName, item.contentType, OfficeProperties.write(bytes, values))
    }

    override fun previews() = converter.available

    /** A value as the text an Office property holds. */
    private fun display(
        actor: Actor,
        def: AttributeDefinition?,
        value: Any,
    ): String? =
        when {
            def?.dataType == DataType.ENUMERATION && def.enumerationId != null -> {
                val e = enumerations.get(actor, def.enumerationId!!)
                val v = e.values.firstOrNull { it.key == value.toString() }
                v?.labels?.get("en") ?: v?.labels?.values?.firstOrNull() ?: value.toString()
            }
            value is Map<*, *> -> listOfNotNull(value["value"], value["unit"]).joinToString(" ")
            value is Collection<*> -> value.joinToString(", ")
            else -> value.toString()
        }.takeIf { it.isNotBlank() }

    /** An Office property's text as an attribute value, or null when it doesn't fit. */
    private fun parse(
        actor: Actor,
        def: AttributeDefinition,
        text: String,
    ): Any? =
        when (def.dataType) {
            DataType.STRING, DataType.URL -> text.lines().first().take(def.maxLength ?: 1000)
            DataType.TEXT -> text.take(def.maxLength ?: 10_000)
            DataType.INTEGER -> text.trim().toLongOrNull()
            DataType.REAL ->
                text
                    .trim()
                    .replace(',', '.')
                    .toBigDecimalOrNull()
                    ?.let(BigDecimal::toDouble)
            DataType.BOOLEAN ->
                when (text.trim().lowercase()) {
                    "true", "yes", "1", "oui", "ja" -> true
                    "false", "no", "0", "non", "nein" -> false
                    else -> null
                }
            DataType.ENUMERATION ->
                def.enumerationId?.let { id ->
                    val t = text.trim()
                    enumerations
                        .get(actor, id)
                        .values
                        .firstOrNull { v ->
                            v.active &&
                                (v.key.equals(t, true) || v.labels.values.any { it.equals(t, true) })
                        }?.key
                }
            else -> null
        }

    /**
     * When an Office file becomes a working copy's primary file, its properties fill the mapped
     * attributes (values that don't fit an attribute are left out), and a PDF is made for previews.
     */
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "content.ContentChanged.v1" || envelope.payload["change"] != "ADDED") return
        val fileName = envelope.payload["fileName"]?.toString() ?: return
        if (!OfficeProperties.isOffice(fileName)) return
        val contentId = UUID.fromString(envelope.payload["contentId"].toString())
        val tenant = TenantId(envelope.tenantId)
        val system = Actor(tenant, PrincipalId("system"), siteAdmin = true)
        if (converter.available) {
            jobs.enqueueSystem(
                system,
                OfficePdfJob.TYPE,
                mapOf("contentId" to contentId.toString()),
                "PDF of $fileName",
            )
        }
        if (envelope.payload["role"] != ContentRole.PRIMARY.name) return
        val person =
            runCatching { directory.get(system, PrincipalId(envelope.actor)) }.getOrNull()
        val actor = Actor(tenant, PrincipalId(envelope.actor), siteAdmin = person?.siteAdmin == true)
        try {
            val objectId = MasterId(UUID.fromString(envelope.payload["objectId"].toString()))
            val obj = versioning.get(actor, objectId)
            if (obj.checkedOutBy != actor.principal.value) return
            val wc = versioning.workingCopy(actor, objectId)
            if (wc.status != IterationStatus.WORKING) return
            val props = OfficeProperties.read(content.open(actor, contentId).use { it.readAllBytes() })
            if (props.isEmpty()) return
            val byName = props.mapKeys { it.key.lowercase() }
            val defs = attributes.effective(actor, obj.typeId).associateBy { it.name }
            val updates =
                mappings(actor, obj.typeId.value)
                    .mapNotNull { m ->
                        val text = byName[m.property.lowercase()] ?: return@mapNotNull null
                        val def = defs[m.attribute] ?: return@mapNotNull null
                        parse(actor, def, text)?.let { m.attribute to it }
                    }.toMap()
            if (updates.isNotEmpty() && updates.any { (k, v) -> wc.attributes[k] != v }) {
                versioning.updateWorkingCopy(actor, objectId, wc.attributes + updates)
            }
        } catch (e: PlmException) {
            // The file stays; its properties just don't fill anything.
            log.info("Office properties of {} not applied: {}", fileName, e.message)
        }
    }
}

/** Makes a PDF of an Office file for previews and thumbnails (roadmap 8.4). */
@Component
internal class OfficePdfJob(
    private val content: ContentService,
    private val converter: OfficeConverter,
) : JobHandler {
    override val type = TYPE
    override val label = "Make a PDF of an Office file"
    override val description = "A PDF of a Word, Excel or PowerPoint file, for previews"
    override val maxAttempts = 2

    override fun title(payload: Map<String, Any?>) = "Office PDF"

    override fun run(context: JobContext): Map<String, Any?> {
        if (!converter.available) throw JobPermanentFailure("LibreOffice isn't installed")
        val contentId =
            UUID.fromString(
                context.payload["contentId"]?.toString() ?: throw JobPermanentFailure("No contentId"),
            )
        val system = context.actor
        val item = content.item(system, contentId)
        if (content.derived(system, contentId, "pdf") != null) return mapOf("pdf" to "already")
        val dir = Files.createTempDirectory("plm-office-in-")
        try {
            val input = dir.resolve(item.fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
            content.open(system, contentId).use { Files.copy(it, input) }
            val pdf =
                converter.toPdf(input) ?: throw JobPermanentFailure("LibreOffice couldn't convert ${item.fileName}")
            content.putDerived(contentId, "pdf", "application/pdf", pdf)
            context.progress(1, 1, "PDF made")
            return mapOf("pdf" to Files.size(pdf))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    companion object {
        const val TYPE = "office.pdf"
    }
}
