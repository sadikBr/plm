package com.plm.search.domain

import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private val log = LoggerFactory.getLogger(Indexer::class.java)
private const val MAX_FILE = 4L * 1024 * 1024
private const val MAX_TEXT = 100_000

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class SearchConfiguration {
    /** `plm.search.engine`: `postgres` (default, built in) or `opensearch` (`plm.search.opensearch.url`, `.index`). */
    @Bean
    fun searchIndex(
        jdbc: JdbcClient,
        @Value("\${plm.search.engine:postgres}") engine: String,
        @Value("\${plm.search.opensearch.url:http://localhost:9200}") url: String,
        @Value("\${plm.search.opensearch.index:plm-objects}") index: String,
    ): SearchIndex = if (engine == "opensearch") OpenSearchIndex(url.trimEnd('/'), index) else PostgresSearchIndex(jdbc)
}

/**
 * Keeps the index up to date (roadmap 3.6). Object events queue the object in the transaction
 * that changed it; the indexer drains the queue every few seconds, reading each object's latest
 * version (values, file names, file contents) and sending it to the engine.
 */
private val REFERENCED_CHANGES =
    setOf(
        "versioning.ObjectCheckedIn.v1",
        "versioning.ObjectRenamed.v1",
        "versioning.ObjectStateChanged.v1",
        "versioning.ObjectRevised.v1",
    )

@Component
internal class Indexer(
    private val jdbc: JdbcClient,
    private val index: SearchIndex,
    private val versioning: VersioningService,
    private val content: ContentService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @param:Value("\${plm.search.indexing:true}") private val enabled: Boolean,
) {
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        val type = envelope.eventType
        if (!type.startsWith("versioning.") || type.startsWith("versioning.Folder")) return
        val id = envelope.payload["objectId"]?.toString() ?: return
        queue(UUID.fromString(id), envelope.tenantId, type == "versioning.ObjectDeleted.v1")
        // Objects referencing this one show its values through alias attributes (roadmap 4.12).
        if (type in REFERENCED_CHANGES) {
            val system = Actor(TenantId(envelope.tenantId), PrincipalId("system"), siteAdmin = true)
            versioning
                .referencedBy(system, MasterId(UUID.fromString(id)))
                .map { it.id }
                .distinct()
                .forEach { queue(it, envelope.tenantId) }
        }
    }

    fun queue(
        objectId: UUID,
        tenant: UUID,
        deleted: Boolean = false,
    ) {
        jdbc
            .sql(
                """
                insert into search_pending (object_id, tenant_id, deleted, queued_at) values (:id, :tenant, :deleted, :now)
                on conflict (object_id) do update set deleted = excluded.deleted, queued_at = excluded.queued_at
                """.trimIndent(),
            ).param("id", objectId)
            .param("tenant", tenant)
            .param("deleted", deleted)
            .param("now", Timestamp.from(clock.instant()))
            .update()
    }

    @Scheduled(fixedDelayString = "\${plm.search.interval:2s}", initialDelayString = "\${plm.search.initial-delay:5s}")
    fun tick() {
        if (!enabled) return
        try {
            drain()
        } catch (e: Exception) {
            log.warn("Indexing failed: {}", e.toString())
        }
    }

    /** Indexes what's queued, in batches; returns how many objects were handled. */
    fun drain(): Int {
        var total = 0
        while (true) {
            val batch =
                tx.execute {
                    jdbc
                        .sql(
                            """
                            delete from search_pending where object_id in (
                                select object_id from search_pending order by queued_at limit 100 for update skip locked)
                            returning object_id, tenant_id, deleted
                            """.trimIndent(),
                        ).query {
                            rs,
                            _,
                            ->
                            Triple(
                                rs.getObject("object_id", UUID::class.java),
                                TenantId(rs.getObject("tenant_id", UUID::class.java)),
                                rs.getBoolean("deleted"),
                            )
                        }.list()
                }
            if (batch.isEmpty()) return total
            for ((tenant, items) in batch.groupBy { it.second }) {
                val system = Actor(tenant, PrincipalId("system"), siteAdmin = true)
                val docs = mutableListOf<IndexDocument>()
                val gone = mutableListOf<UUID>()
                for ((id, _, deleted) in items) {
                    if (deleted) {
                        gone += id
                        continue
                    }
                    val doc = runCatching { document(system, id) }.getOrNull()
                    if (doc == null) gone += id else docs += doc
                }
                index.upsert(docs)
                index.delete(tenant, gone)
            }
            total += batch.size
        }
    }

    /** Every object of [tenant], queued again. */
    fun queueAll(tenant: TenantId): Int =
        jdbc
            .sql(
                """
                insert into search_pending (object_id, tenant_id, deleted, queued_at)
                select id, tenant_id, false, :now from object_master where tenant_id = :tenant
                on conflict (object_id) do update set queued_at = excluded.queued_at, deleted = false
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("now", Timestamp.from(clock.instant()))
            .update()

    private fun document(
        system: Actor,
        id: UUID,
    ): IndexDocument {
        val obj = versioning.get(system, MasterId(id))
        val values =
            obj.latest.attributes
                .filterValues { it != null && it.toString().isNotBlank() }
                .mapValues { (_, v) ->
                    if (v is Map<*, *> &&
                        v.containsKey("value")
                    ) {
                        "${v["value"]} ${v["unit"] ?: ""}".trim()
                    } else {
                        v.toString()
                    }
                }
        val files = content.list(system, obj.id)
        val texts =
            files.mapNotNull { f ->
                if (f.size > MAX_FILE) return@mapNotNull null
                runCatching {
                    when {
                        f.contentType.startsWith("text/") ||
                            f.contentType in setOf("application/json", "application/xml") ->
                            content.open(system, f.id).use { it.readNBytes(MAX_TEXT).decodeToString() }
                        f.contentType == "application/pdf" ->
                            content.open(system, f.id).use {
                                Loader.loadPDF(it.readAllBytes()).use { pdf ->
                                    PDFTextStripper().getText(pdf).take(MAX_TEXT)
                                }
                            }
                        else -> null
                    }
                }.getOrNull()
            }
        val body =
            listOf(
                values.filterValues { it != "true" && it != "false" }.values.joinToString(" "),
                files.joinToString(" ") {
                    it.fileName + " " + (it.description ?: "")
                },
                texts.joinToString("\n"),
            ).joinToString("\n")
                .take(4 * MAX_TEXT)
        return IndexDocument(
            objectId = obj.id.value,
            tenant = system.tenant,
            contextId = obj.contextId.value,
            typeId = obj.typeId.value,
            state = obj.latest.state,
            number = obj.number,
            name = obj.name,
            attributes = values,
            body = body,
            modifiedAt = obj.latest.checkedInAt ?: obj.latest.createdAt,
        )
    }
}
