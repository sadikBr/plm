package com.plm.content

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.content.api.AttachContent
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.content.api.RequestUpload
import com.plm.content.api.ThumbnailStatus
import com.plm.content.domain.DefaultContentService
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.MutableClock
import plm.testing.PlmIntegrationTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.time.Duration
import java.util.UUID
import javax.imageio.ImageIO

/** Object content in the vault (roadmap 3.2). */
@PlmIntegrationTest
internal class ContentIT(
    @Autowired private val content: ContentService,
    @Autowired private val vault: DefaultContentService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val clock: MutableClock,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val stranger = Actor(TenantId.DEFAULT, PrincipalId("stranger-${UUID.randomUUID()}"))

    private fun code(call: () -> Unit) = (runCatching(call).exceptionOrNull() as PlmException).code

    private fun part(): MasterId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Vault ${UUID.randomUUID()}"),
            )
        val product = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Drone")).id
        teams.put(admin, product, alice.principal, TeamRole.MEMBER)
        return versioning
            .create(
                alice,
                CreateVersionedObject(
                    TypeId.PART,
                    product,
                    "Frame",
                    attributes =
                        mapOf(
                            "material" to "CF",
                        ),
                ),
            ).id
    }

    private fun query(
        url: String,
        key: String,
    ) = url
        .substringAfter('?')
        .split('&')
        .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }[key]!!

    /** Requests an upload, sends [bytes] to its signed URL, returns the upload id. */
    private fun upload(
        name: String,
        bytes: ByteArray,
        type: String? = null,
    ): UUID {
        val ticket = content.requestUpload(alice, RequestUpload(name, type, bytes.size.toLong()))
        vault.receive(
            ticket.uploadId,
            query(ticket.uploadUrl, "exp"),
            query(ticket.uploadUrl, "sig"),
            bytes.inputStream(),
        )
        return ticket.uploadId
    }

    private fun fetch(url: String): ByteArray {
        val (stream, _) =
            vault.serve(
                query(url, "k"),
                query(url, "n"),
                query(url, "t"),
                query(url, "d"),
                query(url, "exp"),
                query(url, "sig"),
            )
        return stream.use { it.readAllBytes() }
    }

    @Test
    fun `files go to the working copy, become the object's at check-in, and follow check-out and revise`() {
        val id = part()
        assertThat(code { content.attach(alice, id, AttachContent(UUID.randomUUID())) }).isEqualTo("NOT_CHECKED_OUT")

        versioning.checkOut(alice, id)
        val spec = "Frame spec v1".toByteArray()
        val primary =
            content.attach(
                alice,
                id,
                AttachContent(upload("frame-spec.txt", spec), ContentRole.PRIMARY, "Specification"),
            )
        assertThat(primary.contentType).isEqualTo("text/plain")
        assertThat(primary.size).isEqualTo(spec.size.toLong())
        assertThat(content.list(alice, id)).isEmpty()
        assertThat(content.list(alice, id, workingCopy = true).map { it.fileName }).containsExactly("frame-spec.txt")

        versioning.checkIn(alice, id, "With its spec")
        val latest = content.list(alice, id)
        assertThat(latest.map { it.fileName }).containsExactly("frame-spec.txt")
        assertThat(fetch(content.download(alice, latest[0].id).url)).isEqualTo(spec)

        // Check out: the working copy starts with the same files; changes stay there until check-in.
        versioning.checkOut(alice, id)
        val carried = content.list(alice, id, workingCopy = true).single()
        assertThat(carried.carriedOver).isTrue()
        content.attach(alice, id, AttachContent(upload("notes.md", "# Notes".toByteArray())))
        val v2 =
            content.attach(
                alice,
                id,
                AttachContent(upload("frame-spec-v2.txt", "Frame spec v2".toByteArray()), ContentRole.PRIMARY),
            )
        assertThat(
            content.list(alice, id, workingCopy = true).map {
                it.fileName
            },
        ).containsExactly("frame-spec-v2.txt", "notes.md")
        versioning.undoCheckOut(alice, id)
        assertThat(content.list(alice, id).map { it.fileName }).containsExactly("frame-spec.txt")
        assertThat(code { content.download(alice, v2.id) }).isEqualTo("CONTENT_NOT_FOUND")

        // A new revision starts with its predecessor's files.
        versioning.setState(admin, id, SetState("RELEASED", override = true))
        versioning.revise(alice, id)
        assertThat(content.list(alice, id).map { it.fileName }).containsExactly("frame-spec.txt")
        assertThat(versioning.history(alice, id).first().version).isEqualTo("B.1")
    }

    @Test
    fun `only people who can read the object get its files, through links that expire`() {
        val id = part()
        versioning.checkOut(alice, id)
        val item = content.attach(alice, id, AttachContent(upload("secret.txt", "classified".toByteArray())))
        // Someone else's working copy is invisible, even to readers.
        assertThat(code { content.download(admin, item.id) }).isEqualTo("CONTENT_NOT_FOUND")
        versioning.checkIn(alice, id)

        val stored = content.list(alice, id).single()
        assertThat(code { content.download(stranger, stored.id) }).isEqualTo("CONTENT_NOT_FOUND")
        assertThat(code { content.list(stranger, id) }).isEqualTo("OBJECT_NOT_FOUND")

        val link = content.download(alice, stored.id, inline = true)
        assertThat(link.url).contains("d=inline")
        val tampered = link.url.replace("secret.txt", "other.txt")
        assertThat(code { fetch(tampered) }).isEqualTo("LINK_EXPIRED")
        clock.advance(Duration.ofMinutes(11))
        assertThat(code { fetch(link.url) }).isEqualTo("LINK_EXPIRED")
    }

    @Test
    fun `uploads are checked for signature, size, single use and owner`() {
        val ticket = content.requestUpload(alice, RequestUpload("a.bin", null, 3))
        assertThat(ticket.uploadUrl).startsWith("/api/v1/vault/uploads/${ticket.uploadId}?")
        assertThat(
            code { vault.receive(ticket.uploadId, query(ticket.uploadUrl, "exp"), "forged", "abc".byteInputStream()) },
        ).isEqualTo("UPLOAD_EXPIRED")
        assertThat(
            code {
                content.requestUpload(alice, RequestUpload("big.bin", null, 3L * 1024 * 1024 * 1024))
            },
        ).isEqualTo("UPLOAD_TOO_LARGE")
        assertThat(code { content.requestUpload(alice, RequestUpload("  ", null, 1)) }).isEqualTo("INVALID_FILE_NAME")

        val id = part()
        versioning.checkOut(alice, id)
        val pending = content.requestUpload(alice, RequestUpload("late.txt", null, 1))
        assertThat(code { content.attach(alice, id, AttachContent(pending.uploadId)) }).isEqualTo("UPLOAD_NOT_RECEIVED")
        val up = upload("once.txt", "x".toByteArray())
        content.attach(alice, id, AttachContent(up))
        assertThat(code { content.attach(alice, id, AttachContent(up)) }).isEqualTo("UPLOAD_ALREADY_USED")
        assertThat(code { content.attach(admin, id, AttachContent(up)) }).isEqualTo("CHECKED_OUT_BY_ANOTHER_USER")
    }

    @Test
    fun `PDF pages are previewed as images`() {
        val id = part()
        versioning.checkOut(alice, id)
        val pdf =
            ByteArrayOutputStream()
                .also { out ->
                    PDDocument().use { doc ->
                        repeat(2) { doc.addPage(PDPage()) }
                        doc.save(out)
                    }
                }.toByteArray()
        val item = content.attach(alice, id, AttachContent(upload("drawing.pdf", pdf), ContentRole.PRIMARY))
        assertThat(item.contentType).isEqualTo("application/pdf")
        val page = content.previewPage(alice, item.id, 2)
        assertThat(page.pages).isEqualTo(2)
        assertThat(ImageIO.read(fetch(page.url).inputStream()).width).isGreaterThan(500)
        assertThat(code { content.previewPage(alice, item.id, 3) }).isEqualTo("PAGE_NOT_FOUND")
        // Rendered once: asked again, it's served as it is.
        assertThat(content.previewPage(alice, item.id, 2).pages).isEqualTo(2)
    }

    @Test
    fun `images get a thumbnail, made in the background`() {
        val id = part()
        versioning.checkOut(alice, id)
        val image = BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.ORANGE
            fillRect(0, 0, 800, 400)
            dispose()
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val item = content.attach(alice, id, AttachContent(upload("frame.png", png), ContentRole.PRIMARY))
        assertThat(item.thumbnail).isEqualTo(ThumbnailStatus.PENDING)

        var tries = 0
        while (content.list(alice, id, workingCopy = true).single().thumbnail == ThumbnailStatus.PENDING &&
            tries++ < 100
        ) {
            Thread.sleep(100)
        }
        assertThat(content.list(alice, id, workingCopy = true).single().thumbnail).isEqualTo(ThumbnailStatus.READY)
        val thumb = ImageIO.read(fetch(content.thumbnail(alice, item.id).url).inputStream())
        assertThat(thumb.width).isEqualTo(320)
        assertThat(thumb.height).isEqualTo(160)
    }
}
