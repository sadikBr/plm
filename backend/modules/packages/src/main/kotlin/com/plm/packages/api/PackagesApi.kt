package com.plm.packages.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** What a package carries besides the objects themselves. */
data class PackageContents(
    /** The objects' files. */
    val files: Boolean = true,
    /** PDFs made from them (published or converted), where there are some. */
    val pdfs: Boolean = false,
    /** Everything in the roots' structures. */
    val structure: Boolean = true,
    /** The documents that describe the parts. */
    val documents: Boolean = true,
)

data class SavePackage(
    val name: String,
    val description: String? = null,
    val rootIds: List<UUID>,
    /** LATEST or RELEASED: which iteration of each object goes in. */
    val spec: String = "LATEST",
    val contents: PackageContents = PackageContents(),
)

/** An object in a package, at the iteration the specification picks. */
data class PackageMember(
    val objectId: UUID,
    val number: String,
    val name: String,
    val type: String,
    val version: String,
    val state: String,
    val iterationId: UUID,
    /** ROOT, STRUCTURE or DOCUMENT: why it is in. */
    val reason: String,
    val files: Int,
)

data class Delivery(
    val id: UUID,
    val packageId: UUID,
    val recipient: String,
    val email: String?,
    val message: String?,
    /** PREPARING, READY, FAILED, REVOKED; READY past [expiresAt] has expired. */
    val status: String,
    val expired: Boolean,
    val expiresAt: Instant,
    /** The download address, relative to the site; works without signing in until it expires. */
    val downloadPath: String,
    val fileName: String?,
    val size: Long?,
    val members: Int?,
    val error: String?,
    val downloads: Int,
    val lastDownloadAt: Instant?,
    val createdAt: Instant,
    val createdBy: String,
)

data class DeliveryDownload(
    val at: Instant,
    val address: String?,
    val userAgent: String?,
)

data class DataPackage(
    val id: UUID,
    val name: String,
    val description: String?,
    val rootIds: List<UUID>,
    val spec: String,
    val contents: PackageContents,
    val deliveries: List<Delivery>,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant,
)

data class Deliver(
    val recipient: String,
    val email: String? = null,
    val message: String? = null,
    /** How long the download works: 1 to 90 days. */
    val days: Int = 14,
)

/** A delivery's file, for the public download. */
class DeliveryFile(
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * Packages and deliveries (roadmap 9.4). A package is a chosen set of objects (its roots, plus
 * their structure and describing documents if asked) under a configuration specification, with
 * files and PDFs if asked. A delivery sends it to someone outside: a zip in the migration format
 * (`plm-migration/1`, docs/migration-format.md), prepared by a job, behind a download link that
 * expires and can be revoked; every download is logged. A received package is imported back
 * through Administration → Migration, as copies or as updates.
 *
 * Packages are their creator's; site administrators see them all. Members are what the creator
 * may read. Errors: `PACKAGE_NOT_FOUND`, `DELIVERY_NOT_FOUND`, `INVALID_PACKAGE`,
 * `DELIVERY_UNAVAILABLE` (expired, revoked or not ready).
 */
interface PackageService {
    fun list(actor: Actor): List<DataPackage>

    fun get(
        actor: Actor,
        id: UUID,
    ): DataPackage

    fun create(
        actor: Actor,
        command: SavePackage,
    ): DataPackage

    fun update(
        actor: Actor,
        id: UUID,
        command: SavePackage,
    ): DataPackage

    fun delete(
        actor: Actor,
        id: UUID,
    )

    /** What the package holds now. */
    fun members(
        actor: Actor,
        id: UUID,
    ): List<PackageMember>

    fun deliver(
        actor: Actor,
        id: UUID,
        command: Deliver,
    ): Delivery

    fun revoke(
        actor: Actor,
        deliveryId: UUID,
    ): Delivery

    fun downloads(
        actor: Actor,
        deliveryId: UUID,
    ): List<DeliveryDownload>

    /** For the public link: logs the download. */
    fun download(
        token: String,
        address: String?,
        userAgent: String?,
    ): DeliveryFile
}
