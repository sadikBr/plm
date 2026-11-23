package com.plm.content.domain

import com.plm.content.api.FileSite
import com.plm.content.api.FileSiteChoice
import com.plm.content.api.FileSiteService
import com.plm.content.api.SaveFileSite
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import com.plm.jobs.api.JobService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import org.springframework.context.annotation.Lazy
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class SiteRow(
    val id: UUID,
    val tenant: UUID,
    val name: String,
    val location: String,
    val baseUrl: String?,
    val active: Boolean,
    val createdAt: java.time.Instant,
)

/** File sites: their stores, copies and preferences (roadmap 9.10). */
@Component
internal class FileSites(
    private val jdbc: JdbcClient,
) {
    private val stores = ConcurrentHashMap<UUID, BlobStore>()

    private fun ResultSet.site() =
        SiteRow(
            getObject("id", UUID::class.java),
            getObject("tenant_id", UUID::class.java),
            getString("name"),
            getString("location"),
            getString("base_url"),
            getBoolean("active"),
            getTimestamp("created_at").toInstant(),
        )

    fun sites(tenant: UUID): List<SiteRow> =
        jdbc
            .sql("select * from file_site where tenant_id = :t order by name")
            .param("t", tenant)
            .query {
                rs,
                _,
                ->
                rs.site()
            }.list()

    fun site(id: UUID): SiteRow? =
        jdbc
            .sql(
                "select * from file_site where id = :id",
            ).param("id", id)
            .query { rs, _ -> rs.site() }
            .optional()
            .orElse(null)

    fun store(site: SiteRow): BlobStore = stores.getOrPut(site.id) { FileBlobStore(Path.of(site.location)) }

    fun forget(id: UUID) {
        stores.remove(id)
    }

    fun hasActiveSites(tenant: UUID): Boolean =
        jdbc
            .sql(
                "select exists (select 1 from file_site where tenant_id = :t and active)",
            ).param("t", tenant)
            .query(Boolean::class.java)
            .single()

    fun replicated(
        key: String,
        site: UUID,
        error: String?,
        clock: Clock,
    ) {
        jdbc
            .sql(
                """
                insert into blob_replica (blob_key, site_id, status, error, replicated_at) values (:k, :s, :status, :e, :at)
                on conflict (blob_key, site_id) do update set status = excluded.status, error = excluded.error, replicated_at = excluded.replicated_at
                """,
            ).param("k", key)
            .param("s", site)
            .param("status", if (error == null) "READY" else "FAILED")
            .param("e", error?.take(500))
            .param("at", Timestamp.from(clock.instant()))
            .update()
    }

    fun ready(
        key: String,
        site: UUID,
    ): Boolean =
        jdbc
            .sql("select exists (select 1 from blob_replica where blob_key = :k and site_id = :s and status = 'READY')")
            .param("k", key)
            .param("s", site)
            .query(Boolean::class.java)
            .single()

    /** Keys of the tenant's files the site doesn't have. */
    fun missing(
        tenant: UUID,
        site: UUID,
    ): List<String> =
        jdbc
            .sql(
                """
                select b.key from vault_blob b where b.tenant_id = :t
                  and not exists (select 1 from blob_replica r where r.blob_key = b.key and r.site_id = :s and r.status = 'READY')
                """,
            ).param("t", tenant)
            .param("s", site)
            .query { rs, _ -> rs.getString(1) }
            .list()

    fun counts(site: UUID): Pair<Long, Long> =
        jdbc
            .sql(
                "select count(*) filter (where status = 'READY'), count(*) filter (where status = 'FAILED') from blob_replica where site_id = :s",
            ).param("s", site)
            .query { rs, _ -> rs.getLong(1) to rs.getLong(2) }
            .single()

    fun total(tenant: UUID): Long =
        jdbc
            .sql(
                "select count(*) from vault_blob where tenant_id = :t",
            ).param("t", tenant)
            .query(Long::class.java)
            .single()

    fun preferred(
        tenant: UUID,
        principal: String,
    ): UUID? =
        jdbc
            .sql(
                """
                select p.site_id from file_site_preference p join file_site s on s.id = p.site_id and s.active
                where p.tenant_id = :t and p.principal_id = :p
                """,
            ).param("t", tenant)
            .param("p", principal)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) }
            .optional()
            .orElse(null)

    fun setPreferred(
        tenant: UUID,
        principal: String,
        site: UUID?,
    ) {
        jdbc
            .sql(
                "delete from file_site_preference where tenant_id = :t and principal_id = :p",
            ).param("t", tenant)
            .param("p", principal)
            .update()
        if (site != null) {
            jdbc
                .sql("insert into file_site_preference (tenant_id, principal_id, site_id) values (:t, :p, :s)")
                .param("t", tenant)
                .param("p", principal)
                .param("s", site)
                .update()
        }
    }
}

@Service
internal class DefaultFileSiteService(
    private val sites: FileSites,
    private val jdbc: JdbcClient,
    @param:Lazy private val jobs: JobService,
    private val clock: Clock,
) : FileSiteService {
    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators manage file sites")
    }

    private fun view(s: SiteRow): FileSite {
        val (ready, failed) = sites.counts(s.id)
        return FileSite(
            s.id,
            s.name,
            s.location,
            s.baseUrl,
            s.active,
            ready,
            (sites.total(s.tenant) - ready).coerceAtLeast(0),
            failed,
            s.createdAt,
        )
    }

    private fun row(
        actor: Actor,
        id: UUID,
    ) = sites.site(id)?.takeIf { it.tenant == actor.tenant.value }
        ?: throw NotFoundException("FILE_SITE_NOT_FOUND", "No such file site")

    override fun sites(actor: Actor): List<FileSite> {
        requireAdmin(actor)
        return sites.sites(actor.tenant.value).map(::view)
    }

    private fun validate(command: SaveFileSite) {
        val errors = mutableListOf<String>()
        if (command.name.isBlank()) errors += "Name the site"
        val location = runCatching { Path.of(command.location.trim()) }.getOrNull()
        if (location == null || !location.isAbsolute) errors += "The location is an absolute directory path"
        if (command.baseUrl != null &&
            command.baseUrl.isNotBlank() &&
            !command.baseUrl.startsWith("http")
        ) {
            errors += "The address is http(s)"
        }
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_FILE_SITE",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
        try {
            Files.createDirectories(location!!)
            if (!Files.isWritable(
                    location,
                )
            ) {
                throw ValidationException("INVALID_FILE_SITE", "The server can't write to ${command.location}")
            }
        } catch (e: java.io.IOException) {
            throw ValidationException(
                "INVALID_FILE_SITE",
                "The server can't use ${command.location}: ${e.message}",
            ).apply {
                initCause(e)
            }
        }
    }

    override fun create(
        actor: Actor,
        command: SaveFileSite,
    ): FileSite {
        requireAdmin(actor)
        validate(command)
        val id = UuidV7.generate(clock)
        try {
            jdbc
                .sql(
                    "insert into file_site (id, tenant_id, name, location, base_url, active, created_at) values (:id, :t, :n, :l, :u, :a, :at)",
                ).param("id", id)
                .param("t", actor.tenant.value)
                .param("n", command.name.trim())
                .param("l", command.location.trim())
                .param("u", command.baseUrl?.trim()?.ifEmpty { null })
                .param("a", command.active)
                .param("at", Timestamp.from(clock.instant()))
                .update()
        } catch (e: DuplicateKeyException) {
            throw ValidationException(
                "INVALID_FILE_SITE",
                "There is already a site ${command.name}",
            ).apply { initCause(e) }
        }
        return view(sites.site(id)!!)
    }

    override fun update(
        actor: Actor,
        id: UUID,
        command: SaveFileSite,
    ): FileSite {
        requireAdmin(actor)
        row(actor, id)
        validate(command)
        jdbc
            .sql("update file_site set name = :n, location = :l, base_url = :u, active = :a where id = :id")
            .param("id", id)
            .param("n", command.name.trim())
            .param("l", command.location.trim())
            .param("u", command.baseUrl?.trim()?.ifEmpty { null })
            .param("a", command.active)
            .update()
        sites.forget(id)
        return view(sites.site(id)!!)
    }

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        row(actor, id)
        jdbc.sql("delete from file_site where id = :id").param("id", id).update()
        sites.forget(id)
    }

    override fun replicateAll(
        actor: Actor,
        id: UUID,
    ): UUID {
        requireAdmin(actor)
        val s = row(actor, id)
        return jobs
            .enqueueSystem(
                actor,
                ReplicationJob.TYPE,
                mapOf("siteId" to s.id.toString()),
                "Copy the vault to ${s.name}",
            ).id
    }

    override fun preferred(actor: Actor) = sites.preferred(actor.tenant.value, actor.principal.value)

    override fun setPreferred(
        actor: Actor,
        siteId: UUID?,
    ) {
        if (siteId != null) row(actor, siteId)
        sites.setPreferred(actor.tenant.value, actor.principal.value, siteId)
    }

    override fun choices(actor: Actor) =
        sites
            .sites(actor.tenant.value)
            .filter {
                it.active
            }.map { FileSiteChoice(it.id, it.name) }
}

/**
 * Copies files to file sites (roadmap 9.10): one new file to every active site (`blobKey`), or
 * everything a site is missing (`siteId`).
 */
@Component
internal class ReplicationJob(
    private val sites: FileSites,
    private val store: BlobStore,
    private val clock: Clock,
) : JobHandler {
    override val type = TYPE
    override val label = "Copy files to file sites"
    override val description = "Keeps the copies at remote file sites up to date"
    override val maxAttempts = 3

    override fun title(payload: Map<String, Any?>) = "Copy files to file sites"

    private fun copy(
        key: String,
        site: SiteRow,
    ): Boolean {
        val temp = Files.createTempFile("plm-replica-", ".bin")
        return try {
            store.open(key).use { input -> Files.newOutputStream(temp).use { input.copyTo(it) } }
            sites.store(site).put(key, temp, "application/octet-stream")
            sites.replicated(key, site.id, null, clock)
            true
        } catch (e: Exception) {
            sites.replicated(key, site.id, e.message ?: e.javaClass.simpleName, clock)
            false
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun run(context: JobContext): Map<String, Any?> {
        val tenant = context.actor.tenant.value
        val key = context.payload["blobKey"] as? String
        val siteId = (context.payload["siteId"] as? String)?.let(UUID::fromString)
        return when {
            key != null -> {
                val targets = sites.sites(tenant).filter { it.active && !sites.ready(key, it.id) }
                val ok = targets.count { copy(key, it) }
                if (ok <
                    targets.size
                ) {
                    throw IllegalStateException(
                        "${targets.size - ok} of ${targets.size} sites didn't take the copy",
                    )
                }
                mapOf("copied" to ok)
            }
            siteId != null -> {
                val site = sites.site(siteId) ?: throw JobPermanentFailure("The site was deleted")
                val keys = sites.missing(tenant, site.id)
                var ok = 0
                keys.forEachIndexed { i, k ->
                    context.checkCancelled()
                    if (copy(k, site)) ok++
                    context.progress(i + 1, keys.size, "$ok copied")
                }
                mapOf("copied" to ok, "failed" to keys.size - ok)
            }
            else -> throw JobPermanentFailure("Nothing to copy")
        }
    }

    companion object {
        const val TYPE = "content.replicate"

        /** The system actor that queues copies of new files. */
        fun system(tenant: UUID) = Actor(TenantId(tenant), PrincipalId("system"), siteAdmin = true)
    }
}
