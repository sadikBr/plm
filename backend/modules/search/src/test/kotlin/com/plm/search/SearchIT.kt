package com.plm.search

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.search.api.SaveSearch
import com.plm.search.api.SavedSearchService
import com.plm.search.api.SearchQuery
import com.plm.search.api.SearchService
import com.plm.search.domain.Indexer
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.VersioningService
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Full-text and attribute search, facets, access and saved searches (roadmap 3.6). */
@PlmIntegrationTest
internal class SearchIT(
    @Autowired private val search: SearchService,
    @Autowired private val saved: SavedSearchService,
    @Autowired private val indexer: Indexer,
    @Autowired private val versioning: VersioningService,
    @Autowired private val organizing: OrganizingService,
    @Autowired private val content: ContentService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val stranger = Actor(TenantId.DEFAULT, PrincipalId("stranger-${UUID.randomUUID()}"))
    private val word = "zx" + UUID.randomUUID().toString().take(6)

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Search ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun create(
        context: ContextId,
        type: TypeId,
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        file: Pair<String, ByteArray>? = null,
    ): MasterId {
        val id = versioning.create(alice, CreateVersionedObject(type, context, name, attributes = attributes)).id
        if (file != null) {
            versioning.checkOut(alice, id)
            val type = if (file.first.endsWith(".pdf")) "application/pdf" else "text/plain"
            content.store(alice, id, file.first, type, file.second, ContentRole.PRIMARY)
            versioning.checkIn(alice, id)
        }
        return id
    }

    private fun pdf(text: String): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                PDDocument().use { doc ->
                    val page = PDPage()
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { s ->
                        s.beginText()
                        s.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                        s.newLineAtOffset(50f, 700f)
                        s.showText(text)
                        s.endText()
                    }
                    doc.save(out)
                }
            }.toByteArray()

    @Test
    fun `finds words in values and in files, with snippets, facets and only what you can read`() {
        val p = product()
        val spec =
            create(
                p,
                TypeId.DOCUMENT,
                "Wheel specification",
                file =
                    "spec.txt" to "The wheel hub uses $word bearings, torque 40 Nm.".toByteArray(),
            )
        val drawing =
            create(
                p,
                TypeId.DOCUMENT,
                "Hub drawing",
                file =
                    "hub.pdf" to pdf("Hub drawing with $word tolerances"),
            )
        val part = create(p, TypeId.PART, "Hub $word", mapOf("material" to "Titanium"))
        create(p, TypeId.PART, "Unrelated bracket", mapOf("material" to "Titanium"))
        indexer.drain()

        val r = search.search(alice, SearchQuery(text = word))
        assertThat(r.hits.map { it.id }).containsExactlyInAnyOrder(spec.value, drawing.value, part.value)
        assertThat(r.hits.first { it.id == spec.value }.snippet).contains("«$word»")
        assertThat(r.hits.first { it.id == drawing.value }.snippet).contains("«$word»")
        assertThat(r.facets["type"]!!.associate { it.key to it.count })
            .containsEntry(TypeId.DOCUMENT.value.toString(), 2)
            .containsEntry(TypeId.PART.value.toString(), 1)

        val parts = search.search(alice, SearchQuery(text = word, typeIds = listOf(TypeId.PART.value)))
        assertThat(parts.hits.map { it.id }).containsExactly(part.value)
        val titanium =
            search.search(
                alice,
                SearchQuery(
                    contextIds = listOf(p.value),
                    attributes =
                        mapOf(
                            "material" to "titan",
                        ),
                ),
            )
        assertThat(titanium.total).isEqualTo(2)

        // A part number typed in pieces still finds it.
        val number = versioning.get(alice, part).number
        assertThat(
            search.search(alice, SearchQuery(text = number.takeLast(4), contextIds = listOf(p.value))).hits.map {
                it.id
            },
        ).contains(part.value)

        assertThat(search.search(stranger, SearchQuery(text = word)).total).isZero()
        assertThat(search.search(stranger, SearchQuery(text = word)).facets["type"]).isEmpty()
    }

    @Test
    fun `changes and deletions reach the index`() {
        val p = product()
        val id = create(p, TypeId.PART, "Axle $word")
        indexer.drain()
        assertThat(search.search(alice, SearchQuery(text = word)).total).isEqualTo(1)

        versioning.checkOut(alice, id)
        content.store(
            alice,
            id,
            "notes.txt",
            "text/plain",
            "now with ${word}extra".toByteArray(),
            ContentRole.ATTACHMENT,
        )
        versioning.checkIn(alice, id)
        indexer.drain()
        assertThat(search.search(alice, SearchQuery(text = "${word}extra")).total).isEqualTo(1)

        organizing.delete(alice, id)
        indexer.drain()
        assertThat(search.search(alice, SearchQuery(text = word)).total).isZero()
    }

    @Test
    fun `searches are saved, and shared ones are seen by others`() {
        val mine =
            saved.save(
                alice,
                SaveSearch(
                    "Titanium parts $word",
                    SearchQuery(
                        attributes =
                            mapOf(
                                "material" to "titanium",
                            ),
                    ),
                ),
            )
        val shared =
            saved.save(
                alice,
                SaveSearch("Released $word", SearchQuery(states = listOf("RELEASED")), shared = true),
            )
        assertThat(saved.list(alice).map { it.id }).contains(mine.id, shared.id)
        assertThat(saved.list(admin).map { it.id }).contains(shared.id).doesNotContain(mine.id)
        assertThat(runCatching { saved.delete(admin, shared.id) }.exceptionOrNull()).hasMessageContaining("owner")
        saved.delete(alice, shared.id)
        assertThat(saved.list(admin).map { it.id }).doesNotContain(shared.id)
    }
}
