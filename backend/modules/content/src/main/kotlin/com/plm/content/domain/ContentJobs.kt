package com.plm.content.domain

import com.plm.content.api.ThumbnailStatus
import com.plm.content.infra.ContentRepository
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import javax.imageio.ImageIO

/** Makes a small PNG of an image or of a PDF's first page (roadmap 3.2). Queued when a file is added. */
@Component
internal class ThumbnailJob(
    private val content: ContentRepository,
    private val store: BlobStore,
    private val tx: TransactionTemplate,
) : JobHandler {
    override val type = TYPE
    override val label = "Make a thumbnail"
    override val description = "A small picture of an image or of a PDF's first page"
    override val maxAttempts = 2

    override fun title(payload: Map<String, Any?>) = "Thumbnail"

    override fun run(context: JobContext): Map<String, Any?> {
        val key = context.payload["blobKey"] as? String ?: throw JobPermanentFailure("No blobKey")
        val blob = content.blob(key) ?: throw JobPermanentFailure("No such file")
        if (blob.thumbnail == ThumbnailStatus.READY) return mapOf("thumbnail" to blob.thumbnailKey)
        val source: BufferedImage? =
            try {
                store.open(key).use { input ->
                    if (blob.contentType == "application/pdf") {
                        Loader.loadPDF(input.readAllBytes()).use { pdf -> PDFRenderer(pdf).renderImageWithDPI(0, 48f) }
                    } else {
                        ImageIO.read(input)
                    }
                }
            } catch (e: Exception) {
                context.log("Couldn't read the file: ${e.message}", "WARN")
                null
            }
        if (source == null) {
            tx.executeWithoutResult { content.thumbnail(key, ThumbnailStatus.FAILED, null) }
            return mapOf("thumbnail" to null)
        }
        val thumb = scale(source, MAX)
        val file = Files.createTempFile("plm-thumb-", ".png")
        try {
            ImageIO.write(thumb, "png", file.toFile())
            val thumbKey = "$key.thumb.png"
            store.put(thumbKey, file, "image/png")
            tx.executeWithoutResult { content.thumbnail(key, ThumbnailStatus.READY, thumbKey) }
            context.progress(1, 1, "${thumb.width}×${thumb.height}")
            return mapOf("thumbnail" to thumbKey, "width" to thumb.width, "height" to thumb.height)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun scale(
        source: BufferedImage,
        max: Int,
    ): BufferedImage {
        val ratio = minOf(1.0, max.toDouble() / maxOf(source.width, source.height))
        val w = maxOf(1, (source.width * ratio).toInt())
        val h = maxOf(1, (source.height * ratio).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    companion object {
        const val TYPE = "content.thumbnail"
        const val MAX = 320
    }
}

/** Removes expired upload tickets, and files nothing uses any more, a day after. Scheduled daily. */
@Component
internal class VaultCleanupJob(
    private val content: ContentRepository,
    private val store: BlobStore,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : JobHandler {
    override val type = "content.vault-cleanup"
    override val label = "Clean up the vault"
    override val description = "Removes expired uploads, and files no iteration uses any more (a day after)"

    override fun run(context: JobContext): Map<String, Any?> {
        val before = clock.instant().minus(Duration.ofDays(1))
        val uploads = tx.execute { content.deleteExpiredUploads(before) }
        var removed = 0
        var freed = 0L
        while (true) {
            context.checkCancelled()
            val orphans = tx.execute { content.orphans(before) }
            if (orphans.isEmpty()) break
            for (b in orphans) {
                runCatching { store.delete(b.key) }
                b.thumbnailKey?.let { runCatching { store.delete(it) } }
                for (page in 1..(content.pages(b.key) ?: 0)) runCatching { store.delete("${b.key}.p$page.png") }
                tx.executeWithoutResult { content.deleteBlob(b.key) }
                removed++
                freed += b.size
            }
            context.progress(removed, null, "$removed files removed")
        }
        context.log("$uploads expired uploads and $removed unused files removed ($freed bytes)")
        return mapOf("uploads" to uploads, "files" to removed, "bytes" to freed)
    }
}
