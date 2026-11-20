package com.plm.documents

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.documents.api.CompareService
import com.plm.documents.api.CompareSide
import com.plm.documents.api.DocumentService
import com.plm.documents.api.FileChange
import com.plm.documents.api.LineChange
import com.plm.documents.api.NewFromTemplate
import com.plm.documents.api.SaveAs
import com.plm.documents.api.TemplateSettings
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Templates, Save As and compare (roadmap 3.3). */
@PlmIntegrationTest
internal class DocumentsIT(
    @Autowired private val documents: DocumentService,
    @Autowired private val compare: CompareService,
    @Autowired private val content: ContentService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun code(call: () -> Unit) = (runCatching(call).exceptionOrNull() as PlmException).code

    private fun org() =
        contexts.create(
            admin,
            CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Docs ${UUID.randomUUID()}"),
        )

    private fun product(org: ContextId): ContextId =
        contexts.create(admin, CreateContext(org, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }

    private fun document(
        context: ContextId,
        name: String,
        text: String?,
        attributes: Map<String, Any?> = emptyMap(),
    ): MasterId {
        val id =
            versioning
                .create(
                    alice,
                    CreateVersionedObject(TypeId.DOCUMENT, context, name, attributes = attributes),
                ).id
        if (text != null) {
            versioning.checkOut(alice, id)
            content.store(alice, id, "$name.txt", "text/plain", text.toByteArray(), ContentRole.PRIMARY)
            content.store(alice, id, "notes.md", "text/markdown", "# Notes".toByteArray(), ContentRole.ATTACHMENT)
            versioning.checkIn(alice, id)
        }
        return id
    }

    @Test
    fun `save as copies the latest version's values and files under a new number`() {
        val p = product(org().id)
        val original = document(p, "Test plan", "Step 1\nStep 2\n", mapOf("description" to "Rig tests"))
        val copy = documents.saveAs(alice, original, SaveAs("Test plan (copy)"))
        assertThat(copy.number).isNotEqualTo(versioning.get(alice, original).number)
        assertThat(copy.latest.version).isEqualTo("A.1")
        assertThat(copy.latest.attributes["description"]).isEqualTo("Rig tests")
        assertThat(content.list(alice, copy.id).map { it.fileName }).containsExactly("Test plan.txt", "notes.md")

        val bare = documents.saveAs(alice, original, SaveAs("Without files", withFiles = false))
        assertThat(content.list(alice, bare.id)).isEmpty()
        // Files are only copied into objects just made.
        assertThat(code { content.copyLatest(alice, original, copy.id) }).isEqualTo("NOT_A_NEW_OBJECT")
    }

    @Test
    fun `templates are offered where they apply, and new documents start from them`() {
        val org = org().id
        val p = product(org)
        val elsewhere = product(org().id)
        val source =
            document(
                p,
                "Report template",
                "# Test report\n\nResult: ...\n",
                mapOf(
                    "description" to "Standard test report",
                ),
            )

        assertThat(
            code {
                documents.createTemplate(alice, TemplateSettings(source.value, "Test report"))
            },
        ).isEqualTo("ADMIN_REQUIRED")
        val t =
            documents.createTemplate(
                admin,
                TemplateSettings(source.value, "Test report ${UUID.randomUUID()}", organizationId = org.value),
            )
        assertThat(t.fileName).isEqualTo("Report template.txt")
        assertThat(
            code {
                documents.createTemplate(admin, TemplateSettings(source.value, "Other"))
            },
        ).isEqualTo("DUPLICATE_TEMPLATE")

        assertThat(documents.templates(alice, p.value).map { it.id }).contains(t.id)
        assertThat(documents.templates(alice, elsewhere.value).map { it.id }).doesNotContain(t.id)
        assertThat(documents.templates(alice, p.value, TypeId.PART.value)).noneMatch { it.id == t.id }

        val made =
            documents.newFromTemplate(
                alice,
                NewFromTemplate(
                    t.id,
                    p.value,
                    "Rig report 7",
                    mapOf(
                        "description" to "Rig 7",
                    ),
                ),
            )
        assertThat(made.typeId).isEqualTo(TypeId.DOCUMENT)
        assertThat(made.latest.attributes["description"]).isEqualTo("Rig 7")
        assertThat(
            content
                .list(alice, made.id)
                .single {
                    it.role == ContentRole.PRIMARY
                }.fileName,
        ).isEqualTo("Report template.txt")
        assertThat(
            code {
                documents.newFromTemplate(alice, NewFromTemplate(t.id, elsewhere.value, "x"))
            },
        ).isEqualTo("TEMPLATE_NOT_FOUND")

        documents.updateTemplate(
            admin,
            t.id,
            TemplateSettings(source.value, t.name, organizationId = org.value, active = false),
        )
        assertThat(documents.templates(alice, p.value).map { it.id }).doesNotContain(t.id)
        documents.deleteTemplate(admin, t.id)
    }

    @Test
    fun `compare shows changed values, files and lines`() {
        val p = product(org().id)
        val id = document(p, "Procedure", "Clean\nAssemble\nTest\n", mapOf("description" to "v1"))
        val first = versioning.get(alice, id).latest
        versioning.checkOut(alice, id)
        versioning.updateWorkingCopy(alice, id, first.attributes + ("description" to "v2"))
        content.store(
            alice,
            id,
            "Procedure.txt",
            "text/plain",
            "Clean\nAssemble carefully\nTest\nPack\n".toByteArray(),
            ContentRole.PRIMARY,
        )
        val notes = content.list(alice, id, workingCopy = true).single { it.fileName == "notes.md" }
        content.remove(alice, id, notes.id)
        content.store(alice, id, "photo.txt", "text/plain", "photo".toByteArray(), ContentRole.ATTACHMENT)
        versioning.checkIn(alice, id)

        val c = compare.compare(alice, CompareSide(id.value, first.id.value), CompareSide(id.value))
        assertThat(c.left.version).isEqualTo("A.2")
        assertThat(c.right.version).isEqualTo("A.3")
        assertThat(c.attributes.single { it.name == "description" }).matches {
            it.changed &&
                it.left == "v1" &&
                it.right == "v2"
        }
        assertThat(c.files.associate { it.fileName to it.change })
            .containsEntry("Procedure.txt", FileChange.CHANGED)
            .containsEntry("notes.md", FileChange.REMOVED)
            .containsEntry("photo.txt", FileChange.ADDED)
        val text = c.primaryText!!
        assertThat(text.added).isEqualTo(2)
        assertThat(text.removed).isEqualTo(1)
        assertThat(text.lines.filter { it.change != LineChange.SAME }.map { "${it.change} ${it.text}" })
            .containsExactly("REMOVED Assemble", "ADDED Assemble carefully", "ADDED Pack")
    }
}
