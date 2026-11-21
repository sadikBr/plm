package com.plm.content.domain

import com.plm.content.api.AttachContent
import com.plm.content.api.ContentChanged
import com.plm.content.api.ContentItem
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.content.api.FileLink
import com.plm.content.api.PreviewPage
import com.plm.content.api.RequestUpload
import com.plm.content.api.ThumbnailStatus
import com.plm.content.api.UploadTicket
import com.plm.content.infra.Blob
import com.plm.content.infra.ContentRepository
import com.plm.content.infra.StoredItem
import com.plm.content.infra.Upload
import com.plm.jobs.api.JobService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.UuidV7
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.unit.DataSize
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import javax.imageio.ImageIO

/** File types we make thumbnails of. */
internal val THUMBNAILABLE = setOf("image/png", "image/jpeg", "image/gif", "image/bmp", "application/pdf")

@Service
internal class DefaultContentService(
    private val content: ContentRepository,
    private val store: BlobStore,
    private val signer: Signer,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val events: DomainEvents,
    private val jobs: JobService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @Value("\${plm.vault.max-size:2GB}") maxSize: DataSize,
    @param:Value("\${plm.vault.link-lifetime:10m}") private val linkLifetime: Duration,
) : ContentService {
    val maxBytes: Long = maxSize.toBytes()

    // ---------- Uploading ----------

    @Transactional
    override fun requestUpload(
        actor: Actor,
        request: RequestUpload,
    ): UploadTicket {
        val name = fileName(request.fileName)
        if (request.size != null && request.size > maxBytes) tooLarge()
        val now = clock.instant()
        val id = UuidV7.generate(clock)
        val expires = now.plus(Duration.ofHours(1))
        val type = request.contentType?.takeIf { it.isNotBlank() && it.length < 200 } ?: guessType(name)
        content.insertUpload(
            Upload(id, actor.tenant, actor.principal.value, name, type, request.size, null, expires, null),
            now,
        )
        val exp = expires.epochSecond.toString()
        return UploadTicket(
            id,
            "/api/v1/vault/uploads/$id?exp=$exp&sig=${signer.sign("upload", id.toString(), exp)}",
            expires,
            maxBytes,
        )
    }

    /** The bytes of an upload, sent to its signed URL: stored as a blob, checked by size. */
    fun receive(
        uploadId: UUID,
        exp: String,
        signature: String,
        body: InputStream,
    ): Map<String, Any?> {
        if (!signer.valid(signature, "upload", uploadId.toString(), exp) ||
            (exp.toLongOrNull() ?: 0) < clock.instant().epochSecond
        ) {
            throw ValidationException(
                "UPLOAD_EXPIRED",
                "This upload address is invalid or has expired: ask for a new one",
            )
        }
        val upload = content.upload(uploadId) ?: throw NotFoundException("UPLOAD_NOT_FOUND", "There's no such upload")
        if (upload.blobKey != null) throw ConflictException("UPLOAD_ALREADY_RECEIVED", "This upload was already sent")
        val temp = Files.createTempFile("plm-upload-", ".bin")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            DigestInputStream(body, digest).use { input ->
                Files.newOutputStream(temp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        if (size > maxBytes) tooLarge()
                        out.write(buffer, 0, n)
                    }
                }
            }
            val key = "${upload.tenant.value}/$uploadId"
            store.put(key, temp, upload.contentType)
            val sha = HexFormat.of().formatHex(digest.digest())
            tx.executeWithoutResult {
                content.insertBlob(
                    upload.tenant,
                    Blob(
                        key,
                        size,
                        sha,
                        upload.contentType,
                        null,
                        if (upload.contentType in
                            THUMBNAILABLE
                        ) {
                            ThumbnailStatus.PENDING
                        } else {
                            ThumbnailStatus.NONE
                        },
                    ),
                    upload.createdBy,
                    clock.instant(),
                )
                content.received(uploadId, key)
            }
            return mapOf("uploadId" to uploadId, "size" to size, "sha256" to sha)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    // ---------- Reading ----------

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        objectId: MasterId,
        iterationId: UUID?,
        workingCopy: Boolean,
    ): List<ContentItem> {
        val obj = versioning.get(actor, objectId)
        val iteration =
            when {
                workingCopy -> versioning.workingCopy(actor, objectId).id.value
                iterationId != null ->
                    versioning
                        .history(actor, objectId)
                        .firstOrNull { it.id.value == iterationId }
                        ?.id
                        ?.value
                        ?: throw NotFoundException("ITERATION_NOT_FOUND", "There's no such iteration of ${obj.number}")
                else -> obj.latest.id.value
            }
        return content.items(iteration).map { it.item }
    }

    @Transactional(readOnly = true)
    override fun download(
        actor: Actor,
        contentId: UUID,
        inline: Boolean,
    ): FileLink {
        val s = readable(actor, contentId)
        return link(s.blobKey, s.item.fileName, s.item.contentType, inline)
    }

    @Transactional(readOnly = true)
    override fun thumbnail(
        actor: Actor,
        contentId: UUID,
    ): FileLink {
        val s = readable(actor, contentId)
        val key =
            s.thumbnailKey ?: throw NotFoundException("NO_THUMBNAIL", "There's no thumbnail of ${s.item.fileName}")
        return link(key, "thumbnail.png", "image/png", true)
    }

    override fun previewPage(
        actor: Actor,
        contentId: UUID,
        page: Int,
    ): PreviewPage {
        val s = tx.execute { readable(actor, contentId) }
        if (s.item.contentType != "application/pdf") {
            throw ValidationException("NOT_A_PDF", "Only PDF files are previewed page by page")
        }
        var pages = content.pages(s.blobKey)
        val key = "${s.blobKey}.p$page.png"
        val cached = pages != null && page in 1..pages && runCatching { store.open(key).close() }.isSuccess
        if (!cached) {
            store.open(s.blobKey).use { input ->
                Loader.loadPDF(input.readAllBytes()).use { pdf ->
                    pages = pdf.numberOfPages
                    tx.executeWithoutResult { content.setPages(s.blobKey, pdf.numberOfPages) }
                    if (page !in 1..pdf.numberOfPages) {
                        throw NotFoundException("PAGE_NOT_FOUND", "${s.item.fileName} has ${pdf.numberOfPages} pages")
                    }
                    val image = PDFRenderer(pdf).renderImageWithDPI(page - 1, 110f)
                    val temp = Files.createTempFile("plm-page-", ".png")
                    try {
                        ImageIO.write(image, "png", temp.toFile())
                        store.put(key, temp, "image/png")
                    } finally {
                        Files.deleteIfExists(temp)
                    }
                }
            }
        }
        val link = link(key, "${s.item.fileName}-page-$page.png", "image/png", true)
        return PreviewPage(link.url, link.expiresAt, page, pages!!)
    }

    /**
     * Files belong to documents (their primary content and attachments); parts, like other
     * structured objects, describe things and point to documents for files. `CONTENT_NOT_ALLOWED`.
     */
    private fun isDocument(
        actor: Actor,
        objectId: MasterId,
    ) = types.get(actor, versioning.get(actor, objectId).typeId).baseClass == BaseClass.DOCUMENT

    private fun requireDocument(
        actor: Actor,
        objectId: MasterId,
    ) {
        val type = types.get(actor, versioning.get(actor, objectId).typeId)
        if (type.baseClass != BaseClass.DOCUMENT) {
            throw ValidationException(
                "CONTENT_NOT_ALLOWED",
                "Only documents hold files: a ${type.displayName} can't have primary content or attachments",
                mapOf("typeId" to type.id.value),
            )
        }
    }

    @Transactional
    override fun copyLatest(
        actor: Actor,
        from: MasterId,
        to: MasterId,
    ): Int {
        val source = versioning.get(actor, from)
        val target = versioning.get(actor, to)
        // Only documents hold files: a part saved as a new one (even one with files from before this
        // rule) starts without any.
        if (content.items(source.latest.id.value).isEmpty() || !isDocument(actor, to)) return 0
        if (target.latest.createdBy != actor.principal.value ||
            target.latest.iteration != 1 ||
            content.items(target.latest.id.value).isNotEmpty()
        ) {
            throw ConflictException("NOT_A_NEW_OBJECT", "Files are only copied into an object you just created")
        }
        return content.copyInto(
            source.latest.id.value,
            to.value,
            target.latest.id.value,
            actor.principal.value,
            clock.instant(),
        ) {
            UuidV7.generate(clock)
        }
    }

    override fun open(
        actor: Actor,
        contentId: UUID,
    ): InputStream {
        val s = tx.execute { readable(actor, contentId) }
        return store.open(s.blobKey)
    }

    /** A signed link to a stored file, valid for a few minutes. */
    private fun link(
        key: String,
        fileName: String,
        contentType: String,
        inline: Boolean,
    ): FileLink {
        val expires = clock.instant().plus(linkLifetime)
        val exp = expires.epochSecond.toString()
        val disposition = if (inline) "inline" else "attachment"
        val sig = signer.sign("file", key, fileName, contentType, disposition, exp)

        fun q(v: String) = URLEncoder.encode(v, Charsets.UTF_8)
        return FileLink(
            "/api/v1/vault/files?k=${q(key)}&n=${q(fileName)}&t=${q(contentType)}&d=$disposition&exp=$exp&sig=$sig",
            expires,
        )
    }

    /** Serves a signed file link: what to send and how. */
    fun serve(
        key: String,
        fileName: String,
        contentType: String,
        disposition: String,
        exp: String,
        signature: String,
    ): Pair<InputStream, Long?> {
        if (!signer.valid(signature, "file", key, fileName, contentType, disposition, exp) ||
            (exp.toLongOrNull() ?: 0) < clock.instant().epochSecond
        ) {
            throw ValidationException(
                "LINK_EXPIRED",
                "This link is invalid or has expired: open the file again from its object",
            )
        }
        val size = content.blob(key)?.size
        return store.open(key) to size
    }

    // ---------- Changing (the actor's working copy) ----------

    @Transactional
    override fun attach(
        actor: Actor,
        objectId: MasterId,
        request: AttachContent,
    ): ContentItem {
        val wc = versioning.workingCopy(actor, objectId)
        val upload = content.upload(request.uploadId)
        if (upload == null || upload.tenant != actor.tenant || upload.createdBy != actor.principal.value) {
            throw NotFoundException("UPLOAD_NOT_FOUND", "There's no such upload")
        }
        val blobKey = upload.blobKey ?: throw ConflictException("UPLOAD_NOT_RECEIVED", "The file hasn't been sent yet")
        if (upload.usedAt != null) throw ConflictException("UPLOAD_ALREADY_USED", "This upload is already attached")
        val item = add(actor, objectId, wc.id.value, request.role, upload.fileName, request.description, blobKey)
        content.used(upload.id, clock.instant())
        return item
    }

    @Transactional
    override fun store(
        actor: Actor,
        objectId: MasterId,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        role: ContentRole,
        description: String?,
    ): ContentItem {
        val wc = versioning.workingCopy(actor, objectId)
        val name = fileName(fileName)
        if (bytes.size > maxBytes) tooLarge()
        val key = "${actor.tenant.value}/${UuidV7.generate(clock)}"
        val temp = Files.createTempFile("plm-store-", ".bin")
        try {
            Files.write(temp, bytes)
            store.put(key, temp, contentType)
        } finally {
            Files.deleteIfExists(temp)
        }
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        content.insertBlob(
            actor.tenant,
            Blob(
                key,
                bytes.size.toLong(),
                sha,
                contentType,
                null,
                if (contentType in
                    THUMBNAILABLE
                ) {
                    ThumbnailStatus.PENDING
                } else {
                    ThumbnailStatus.NONE
                },
            ),
            actor.principal.value,
            clock.instant(),
        )
        return add(actor, objectId, wc.id.value, role, name, description, key)
    }

    private fun add(
        actor: Actor,
        objectId: MasterId,
        iterationId: UUID,
        role: ContentRole,
        fileName: String,
        description: String?,
        blobKey: String,
    ): ContentItem {
        requireDocument(actor, objectId)
        if (role == ContentRole.PRIMARY) {
            // One primary file: a new one replaces the old.
            content
                .items(
                    iterationId,
                ).filter { it.item.role == ContentRole.PRIMARY }
                .forEach { content.deleteItem(it.item.id) }
        }
        val id = UuidV7.generate(clock)
        content.insertItem(
            actor.tenant,
            id,
            objectId.value,
            iterationId,
            role,
            fileName,
            description?.trim()?.ifEmpty { null },
            blobKey,
            actor.principal.value,
            clock.instant(),
        )
        events.publish(actor, ContentChanged(objectId.value, iterationId, id, fileName, role, "ADDED"))
        val blob = content.blob(blobKey)!!
        if (blob.thumbnail == ThumbnailStatus.PENDING) {
            jobs.enqueueSystem(actor, ThumbnailJob.TYPE, mapOf("blobKey" to blobKey), "Thumbnail of $fileName")
        }
        return content.item(id)!!.item
    }

    @Transactional
    override fun rename(
        actor: Actor,
        objectId: MasterId,
        contentId: UUID,
        fileName: String,
        description: String?,
    ): ContentItem {
        val s = ofWorkingCopy(actor, objectId, contentId)
        val name = fileName(fileName)
        content.updateItem(contentId, name, description?.trim()?.ifEmpty { null })
        events.publish(
            actor,
            ContentChanged(objectId.value, s.item.iterationId, contentId, name, s.item.role, "RENAMED"),
        )
        return content.item(contentId)!!.item
    }

    @Transactional
    override fun remove(
        actor: Actor,
        objectId: MasterId,
        contentId: UUID,
    ) {
        val s = ofWorkingCopy(actor, objectId, contentId)
        content.deleteItem(contentId)
        events.publish(
            actor,
            ContentChanged(objectId.value, s.item.iterationId, contentId, s.item.fileName, s.item.role, "REMOVED"),
        )
    }

    // ---------- Checks ----------

    /** Content the actor may read: of an object they can read; a working copy's only for its owner. */
    private fun readable(
        actor: Actor,
        contentId: UUID,
    ): StoredItem {
        val s = content.item(contentId)
        val missing = NotFoundException("CONTENT_NOT_FOUND", "There's no such file")
        if (s == null || s.tenant != actor.tenant) throw missing
        try {
            versioning.get(actor, MasterId(s.item.masterId))
        } catch (e: NotFoundException) {
            throw missing
        }
        if (s.iterationStatus == "WORKING" && s.checkedOutBy != actor.principal.value) throw missing
        return s
    }

    private fun ofWorkingCopy(
        actor: Actor,
        objectId: MasterId,
        contentId: UUID,
    ): StoredItem {
        val wc = versioning.workingCopy(actor, objectId)
        val s = content.item(contentId)
        if (s == null || s.item.iterationId != wc.id.value) {
            throw NotFoundException("CONTENT_NOT_FOUND", "There's no such file in your working copy")
        }
        return s
    }

    private fun fileName(name: String): String {
        val n = name.trim().substringAfterLast('/').substringAfterLast('\\')
        if (n.isEmpty() || n.length > 255 || n.any { it.isISOControl() } || n == "." || n == "..") {
            throw ValidationException("INVALID_FILE_NAME", "Give the file a name (at most 255 characters)")
        }
        return n
    }

    private fun tooLarge(): Nothing =
        throw ValidationException(
            "UPLOAD_TOO_LARGE",
            "Files can be at most ${DataSize.ofBytes(maxBytes).toMegabytes()} MB",
        )

    companion object {
        private val TYPES =
            mapOf(
                "pdf" to "application/pdf",
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "gif" to "image/gif",
                "bmp" to "image/bmp",
                "svg" to "image/svg+xml",
                "txt" to "text/plain",
                "csv" to "text/csv",
                "md" to "text/markdown",
                "json" to "application/json",
                "xml" to "application/xml",
                "zip" to "application/zip",
                "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "step" to "model/step",
                "stp" to "model/step",
                "stl" to "model/stl",
                "dxf" to "image/vnd.dxf",
            )

        fun guessType(fileName: String): String =
            TYPES[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    }
}

/** Content follows the iteration it's based on: a working copy gets the latest's, a revision its predecessor's. */
@Service
internal class ContentCarryOver(
    private val content: ContentRepository,
    private val clock: Clock,
) {
    @EventListener
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType == "versioning.ObjectCheckedOut.v1" ||
            envelope.eventType == "versioning.ObjectRevised.v1"
        ) {
            val iterationId = UUID.fromString(envelope.payload["iterationId"].toString())
            content.carryOver(iterationId) { n -> List(n) { UuidV7.generate(clock) } }
        }
    }
}
