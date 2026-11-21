package com.plm.publishing

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.publishing.api.PublishRuleSettings
import com.plm.publishing.api.PublishTrigger
import com.plm.publishing.api.PublishingService
import com.plm.publishing.domain.DataSheet
import com.plm.publishing.domain.Pdfs
import com.plm.types.api.CreateSubtype
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** Publishing, watermarks and data sheets (roadmap 6.7). */
@PlmIntegrationTest
internal class PublishingIT(
    @Autowired private val publishing: PublishingService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val content: ContentService,
    @Autowired private val bom: BomService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val types: TypeService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val suffix = UUID.randomUUID().toString().take(6)

    @AfterEach
    fun cleanUp() {
        publishing.rules(admin).filter { suffix in it.name }.forEach { publishing.deleteRule(admin, it.id) }
    }

    private fun text(pdf: ByteArray) = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

    private fun waitReady(id: UUID) =
        (1..120).firstNotNullOfOrNull {
            publishing.publications(alice, id).firstOrNull { it.status != "QUEUED" } ?: run {
                Thread.sleep(250)
                null
            }
        } ?: error("Nothing was published")

    @Test
    fun `releasing a drawing publishes its PDF stamped, and parts publish data sheets`() {
        val org = contexts.create(admin, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Pub $suffix"))
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val drawingType =
            types
                .createSubtype(
                    admin,
                    CreateSubtype(TypeId.DOCUMENT, "Drawing$suffix", "Drawing $suffix"),
                ).id
        val partType = types.createSubtype(admin, CreateSubtype(TypeId.PART, "Sheet$suffix", "Sheet part $suffix")).id
        publishing.saveRule(
            admin,
            null,
            PublishRuleSettings(
                "Released drawings $suffix",
                PublishTrigger.STATE,
                listOf("Drawing$suffix"),
                "RELEASED",
            ),
        )
        publishing.saveRule(
            admin,
            null,
            PublishRuleSettings(
                "Part sheets $suffix",
                PublishTrigger.CHECK_IN,
                listOf("Sheet$suffix"),
                watermark = false,
            ),
        )

        // A drawing with a PDF: released, then published with its number, version and state.
        val drawing = versioning.create(alice, CreateVersionedObject(drawingType, p, "Bracket drawing")).id
        versioning.checkOut(alice, drawing)
        val (sheet, _) =
            Pdfs.datasheet(
                DataSheet("Bracket", "Drawing", listOf("Scale" to "1:1"), emptyList(), "drawing"),
            )
        content.store(alice, drawing, "bracket.pdf", "application/pdf", sheet, ContentRole.PRIMARY)
        versioning.checkIn(alice, drawing)
        assertThat(publishing.publications(alice, drawing.value)).isEmpty() // not on check-in
        versioning.setState(admin, drawing, SetState("RELEASED", override = true))
        val published = waitReady(drawing.value)
        assertThat(published.status).isEqualTo("READY")
        assertThat(published.source).isEqualTo("FILE")
        assertThat(published.rule).isEqualTo("Released drawings $suffix")
        val pdf = text(publishing.download(alice, published.id).bytes)
        val number = versioning.get(alice, drawing).number
        assertThat(pdf).contains("$number A.2 Released").contains("Downloaded by")
        // The diagonal stamp comes out of the text extraction broken up.
        assertThat(pdf).containsPattern("RE\\s*LE\\s*AS\\s*ED")

        // A part without a file: a data sheet, with its structure.
        val child = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Bolt")).id
        val part =
            versioning
                .create(
                    alice,
                    CreateVersionedObject(
                        partType,
                        p,
                        "Bracket",
                        attributes =
                            mapOf(
                                "material" to "AL6061",
                            ),
                    ),
                ).id
        versioning.checkOut(alice, part)
        bom.add(alice, part, AddUsage(child.value, BigDecimal(4)))
        versioning.checkIn(alice, part, "Four bolts")
        val dataSheet = waitReady(part.value)
        assertThat(dataSheet.source).isEqualTo("DATASHEET")
        val sheetText = text(publishing.download(alice, dataSheet.id).bytes)
        assertThat(
            sheetText,
        ).contains("Bracket").contains("AL6061").contains("Bolt").contains("Four bolts").doesNotContain("IN WORK")

        // Any object's data sheet on demand, and PDFs downloaded with a watermark.
        assertThat(text(publishing.datasheet(alice, child.value).bytes)).contains("Bolt").contains("Downloaded by")
        val file = content.list(alice, drawing).single()
        assertThat(text(publishing.watermarked(alice, file.id).bytes)).contains("Downloaded by").contains(number)
    }
}
