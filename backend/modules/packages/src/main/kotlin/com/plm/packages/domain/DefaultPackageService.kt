package com.plm.packages.domain

import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import com.plm.jobs.api.JobService
import com.plm.jobs.api.SubmitJob
import com.plm.packages.api.DataPackage
import com.plm.packages.api.Deliver
import com.plm.packages.api.Delivery
import com.plm.packages.api.DeliveryDownload
import com.plm.packages.api.DeliveryFile
import com.plm.packages.api.PackageContents
import com.plm.packages.api.PackageMember
import com.plm.packages.api.PackageService
import com.plm.packages.api.SavePackage
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

internal data class PackageRow(
    val id: UUID,
    val tenant: UUID,
    val name: String,
    val description: String?,
    val rootIds: List<UUID>,
    val spec: String,
    val contents: PackageContents,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant,
)

internal data class DeliveryRow(
    val id: UUID,
    val tenant: UUID,
    val packageId: UUID,
    val recipient: String,
    val email: String?,
    val message: String?,
    val token: String,
    val expiresAt: Instant,
    val status: String,
    val fileName: String?,
    val size: Long?,
    val members: Int?,
    val error: String?,
    val createdAt: Instant,
    val createdBy: String,
)

@Component
internal class PackageRepository(
    private val jdbc: JdbcClient,
) {
    val json: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun ResultSet.pkg() =
        PackageRow(
            getObject("id", UUID::class.java),
            getObject("tenant_id", UUID::class.java),
            getString("name"),
            getString("description"),
            json.readValue<List<String>>(getString("root_ids")).map(UUID::fromString),
            getString("spec"),
            json.readValue(getString("contents")),
            getTimestamp("created_at").toInstant(),
            getString("created_by"),
            getTimestamp("updated_at").toInstant(),
        )

    private val deliveryColumns =
        "id, tenant_id, package_id, recipient, email, message, token, expires_at, status, file_name, size, members, " +
            "error, created_at, created_by"

    private fun ResultSet.delivery() =
        DeliveryRow(
            getObject("id", UUID::class.java),
            getObject("tenant_id", UUID::class.java),
            getObject("package_id", UUID::class.java),
            getString("recipient"),
            getString("email"),
            getString("message"),
            getString("token"),
            getTimestamp("expires_at").toInstant(),
            getString("status"),
            getString("file_name"),
            getObject("size") as Long?,
            getObject("members") as Int?,
            getString("error"),
            getTimestamp("created_at").toInstant(),
            getString("created_by"),
        )

    fun packages(tenant: UUID): List<PackageRow> =
        jdbc
            .sql("select * from data_package where tenant_id = :t order by updated_at desc")
            .param("t", tenant)
            .query { rs, _ -> rs.pkg() }
            .list()

    fun pkg(id: UUID): PackageRow? =
        jdbc
            .sql("select * from data_package where id = :id")
            .param("id", id)
            .query { rs, _ -> rs.pkg() }
            .optional()
            .orElse(null)

    fun save(p: PackageRow) {
        jdbc
            .sql(
                """
                insert into data_package (id, tenant_id, name, description, root_ids, spec, contents, created_at, created_by, updated_at)
                values (:id, :t, :name, :description, cast(:roots as jsonb), :spec, cast(:contents as jsonb), :created, :by, :updated)
                on conflict (id) do update set name = excluded.name, description = excluded.description,
                    root_ids = excluded.root_ids, spec = excluded.spec, contents = excluded.contents, updated_at = excluded.updated_at
                """,
            ).param("id", p.id)
            .param("t", p.tenant)
            .param("name", p.name)
            .param("description", p.description)
            .param("roots", json.writeValueAsString(p.rootIds.map { it.toString() }))
            .param("spec", p.spec)
            .param("contents", json.writeValueAsString(p.contents))
            .param("created", Timestamp.from(p.createdAt))
            .param("by", p.createdBy)
            .param("updated", Timestamp.from(p.updatedAt))
            .update()
    }

    fun deletePackage(id: UUID) {
        jdbc.sql("delete from data_package where id = :id").param("id", id).update()
    }

    fun deliveries(packageId: UUID): List<DeliveryRow> =
        jdbc
            .sql("select $deliveryColumns from delivery where package_id = :p order by created_at desc")
            .param("p", packageId)
            .query { rs, _ -> rs.delivery() }
            .list()

    fun delivery(id: UUID): DeliveryRow? =
        jdbc
            .sql("select $deliveryColumns from delivery where id = :id")
            .param("id", id)
            .query { rs, _ -> rs.delivery() }
            .optional()
            .orElse(null)

    fun deliveryByToken(token: String): DeliveryRow? =
        jdbc
            .sql("select $deliveryColumns from delivery where token = :t")
            .param("t", token)
            .query { rs, _ -> rs.delivery() }
            .optional()
            .orElse(null)

    fun insertDelivery(d: DeliveryRow) {
        jdbc
            .sql(
                """
                insert into delivery (id, tenant_id, package_id, recipient, email, message, token, expires_at, status, created_at, created_by)
                values (:id, :t, :p, :recipient, :email, :message, :token, :expires, :status, :created, :by)
                """,
            ).param("id", d.id)
            .param("t", d.tenant)
            .param("p", d.packageId)
            .param("recipient", d.recipient)
            .param("email", d.email)
            .param("message", d.message)
            .param("token", d.token)
            .param("expires", Timestamp.from(d.expiresAt))
            .param("status", d.status)
            .param("created", Timestamp.from(d.createdAt))
            .param("by", d.createdBy)
            .update()
    }

    fun ready(
        id: UUID,
        fileName: String,
        bytes: ByteArray,
        members: Int,
    ) {
        jdbc
            .sql(
                "update delivery set status = 'READY', file = :f, file_name = :n, size = :s, members = :m where id = :id",
            ).param("id", id)
            .param("f", bytes)
            .param("n", fileName)
            .param("s", bytes.size.toLong())
            .param("m", members)
            .update()
    }

    fun setStatus(
        id: UUID,
        status: String,
        error: String? = null,
    ) {
        jdbc
            .sql(
                "update delivery set status = :s, error = :e, file = case when :s = 'REVOKED' then null else file end where id = :id",
            ).param("id", id)
            .param("s", status)
            .param("e", error)
            .update()
    }

    fun file(id: UUID): ByteArray? =
        jdbc
            .sql("select file from delivery where id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getBytes(1) }
            .optional()
            .orElse(null)

    fun logDownload(
        id: UUID,
        at: Instant,
        address: String?,
        agent: String?,
    ) {
        jdbc
            .sql("insert into delivery_download (delivery_id, at, address, user_agent) values (:id, :at, :a, :u)")
            .param("id", id)
            .param("at", Timestamp.from(at))
            .param("a", address?.take(100))
            .param("u", agent?.take(300))
            .update()
    }

    fun downloads(id: UUID): List<DeliveryDownload> =
        jdbc
            .sql("select * from delivery_download where delivery_id = :id order by at desc")
            .param("id", id)
            .query {
                rs,
                _,
                ->
                DeliveryDownload(rs.getTimestamp("at").toInstant(), rs.getString("address"), rs.getString("user_agent"))
            }.list()
}

@Service
internal class DefaultPackageService(
    private val repository: PackageRepository,
    private val builder: PackageBuilder,
    private val jobs: JobService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : PackageService {
    private val random = SecureRandom()

    private fun visible(
        actor: Actor,
        p: PackageRow?,
        id: UUID,
    ): PackageRow {
        if (p == null || p.tenant != actor.tenant.value) {
            throw NotFoundException("PACKAGE_NOT_FOUND", "Package $id does not exist", mapOf("id" to id))
        }
        if (p.createdBy != actor.principal.value && !actor.siteAdmin) {
            throw ForbiddenException("ACCESS_DENIED", "This package is someone else's")
        }
        return p
    }

    private fun delivery(
        actor: Actor,
        id: UUID,
    ): DeliveryRow {
        val d = repository.delivery(id) ?: throw NotFoundException("DELIVERY_NOT_FOUND", "Delivery $id does not exist")
        visible(actor, repository.pkg(d.packageId), d.packageId)
        return d
    }

    private fun view(d: DeliveryRow): Delivery {
        val downloads = repository.downloads(d.id)
        return Delivery(
            d.id,
            d.packageId,
            d.recipient,
            d.email,
            d.message,
            d.status,
            d.status == "READY" && d.expiresAt.isBefore(clock.instant()),
            d.expiresAt,
            "/api/v1/deliveries/${d.token}/download",
            d.fileName,
            d.size,
            d.members,
            d.error,
            downloads.size,
            downloads.firstOrNull()?.at,
            d.createdAt,
            d.createdBy,
        )
    }

    private fun view(p: PackageRow) =
        DataPackage(
            p.id,
            p.name,
            p.description,
            p.rootIds,
            p.spec,
            p.contents,
            repository.deliveries(p.id).map(::view),
            p.createdAt,
            p.createdBy,
            p.updatedAt,
        )

    override fun list(actor: Actor): List<DataPackage> =
        repository
            .packages(actor.tenant.value)
            .filter { actor.siteAdmin || it.createdBy == actor.principal.value }
            .map(::view)

    override fun get(
        actor: Actor,
        id: UUID,
    ) = view(visible(actor, repository.pkg(id), id))

    private fun validate(command: SavePackage) {
        val errors = mutableListOf<String>()
        if (command.name.isBlank() || command.name.length > 200) errors += "A name must be 1-200 characters"
        if (command.rootIds.isEmpty()) errors += "Choose at least one object"
        if (command.rootIds.size > 500) errors += "At most 500 objects can be chosen"
        if (command.spec !in setOf("LATEST", "RELEASED")) errors += "The specification is LATEST or RELEASED"
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_PACKAGE",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
    }

    override fun create(
        actor: Actor,
        command: SavePackage,
    ): DataPackage {
        validate(command)
        val now = clock.instant()
        val row =
            PackageRow(
                UuidV7.generate(clock),
                actor.tenant.value,
                command.name.trim(),
                command.description?.trim()?.ifEmpty { null },
                command.rootIds.distinct(),
                command.spec,
                command.contents,
                now,
                actor.principal.value,
                now,
            )
        tx.executeWithoutResult { repository.save(row) }
        return view(row)
    }

    override fun update(
        actor: Actor,
        id: UUID,
        command: SavePackage,
    ): DataPackage {
        validate(command)
        val p = visible(actor, repository.pkg(id), id)
        val row =
            p.copy(
                name = command.name.trim(),
                description = command.description?.trim()?.ifEmpty { null },
                rootIds = command.rootIds.distinct(),
                spec = command.spec,
                contents = command.contents,
                updatedAt = clock.instant(),
            )
        tx.executeWithoutResult { repository.save(row) }
        return view(row)
    }

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        visible(actor, repository.pkg(id), id)
        tx.executeWithoutResult { repository.deletePackage(id) }
    }

    override fun members(
        actor: Actor,
        id: UUID,
    ): List<PackageMember> {
        val p = visible(actor, repository.pkg(id), id)
        return tx.execute { builder.members(actor, builder.resolve(actor, p.rootIds, p.spec, p.contents)) }
    }

    override fun deliver(
        actor: Actor,
        id: UUID,
        command: Deliver,
    ): Delivery {
        val p = visible(actor, repository.pkg(id), id)
        val recipient = command.recipient.trim()
        if (recipient.isEmpty() ||
            recipient.length > 200
        ) {
            throw ValidationException("INVALID_PACKAGE", "Name the recipient")
        }
        if (command.days !in 1..90) throw ValidationException("INVALID_PACKAGE", "A download works for 1 to 90 days")
        val bytes = ByteArray(24).also(random::nextBytes)
        val now = clock.instant()
        val row =
            DeliveryRow(
                UuidV7.generate(clock),
                actor.tenant.value,
                p.id,
                recipient,
                command.email?.trim()?.ifEmpty { null },
                command.message?.trim()?.ifEmpty { null },
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                now.plus(Duration.ofDays(command.days.toLong())),
                "PREPARING",
                null,
                null,
                null,
                null,
                now,
                actor.principal.value,
            )
        tx.executeWithoutResult { repository.insertDelivery(row) }
        jobs.submit(
            actor,
            SubmitJob(
                DeliveryJob.TYPE,
                mapOf(
                    "deliveryId" to row.id.toString(),
                    "package" to p.name,
                    "recipient" to recipient,
                ),
            ),
        )
        return view(row)
    }

    override fun revoke(
        actor: Actor,
        deliveryId: UUID,
    ): Delivery {
        delivery(actor, deliveryId)
        tx.executeWithoutResult { repository.setStatus(deliveryId, "REVOKED") }
        return view(repository.delivery(deliveryId)!!)
    }

    override fun downloads(
        actor: Actor,
        deliveryId: UUID,
    ): List<DeliveryDownload> {
        delivery(actor, deliveryId)
        return repository.downloads(deliveryId)
    }

    override fun download(
        token: String,
        address: String?,
        userAgent: String?,
    ): DeliveryFile {
        val d = repository.deliveryByToken(token)
        val unavailable = ValidationException("DELIVERY_UNAVAILABLE", "This download link has expired or was withdrawn")
        if (d == null || d.status != "READY" || d.expiresAt.isBefore(clock.instant())) throw unavailable
        val bytes = repository.file(d.id) ?: throw unavailable
        tx.executeWithoutResult { repository.logDownload(d.id, clock.instant(), address, userAgent) }
        return DeliveryFile(d.fileName ?: "package.zip", bytes)
    }

    fun prepare(
        actor: Actor,
        deliveryId: UUID,
    ): Int {
        val d = repository.delivery(deliveryId) ?: throw JobPermanentFailure("The delivery was deleted")
        val p = repository.pkg(d.packageId) ?: throw JobPermanentFailure("The package was deleted")
        val (bytes, count) =
            tx.execute {
                val resolved = builder.resolve(actor, p.rootIds, p.spec, p.contents)
                if (resolved.isEmpty()) throw JobPermanentFailure("Nothing in the package can be read any more")
                builder.zip(
                    actor,
                    resolved,
                    p.contents,
                    "Package '${p.name}', delivered to ${d.recipient} on ${LocalDate.ofInstant(
                        d.createdAt,
                        ZoneOffset.UTC,
                    )}",
                ) to
                    resolved.size
            }
        val name =
            p.name
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-')
                .ifEmpty { "package" }
        tx.executeWithoutResult { repository.ready(deliveryId, "$name.zip", bytes, count) }
        return count
    }

    fun failed(
        deliveryId: UUID,
        message: String,
    ) {
        tx.executeWithoutResult { repository.setStatus(deliveryId, "FAILED", message) }
    }
}

/** Prepares a delivery's zip (roadmap 9.4). */
@Component
internal class DeliveryJob(
    private val service: DefaultPackageService,
) : JobHandler {
    override val type = TYPE
    override val label = "Package delivery"
    override val description = "Prepares a package's zip for its recipient"
    override val adminOnly = false
    override val maxAttempts = 1

    override fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) {
        if (payload["deliveryId"] == null) throw ValidationException("INVALID_PACKAGE", "Which delivery?")
    }

    override fun title(payload: Map<String, Any?>) = "Package ${payload["package"]} for ${payload["recipient"]}"

    override fun run(context: JobContext): Map<String, Any?> {
        val id = UUID.fromString(context.payload["deliveryId"].toString())
        try {
            return mapOf("members" to service.prepare(context.actor, id))
        } catch (e: Exception) {
            service.failed(id, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    companion object {
        const val TYPE = "packages.deliver"
    }
}
