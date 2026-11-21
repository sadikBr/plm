package com.plm.bom

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.DocumentLinkService
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.CreateSubtype
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Part-document links (roadmap 4.7). */
@PlmIntegrationTest
internal class DocumentLinksIT(
    @Autowired private val links: DocumentLinkService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val types: TypeService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Links ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun obj(
        p: ContextId,
        type: TypeId,
        name: String,
    ): MasterId = versioning.create(alice, CreateVersionedObject(type, p, name)).id

    @Test
    fun `described-by points at a version, references at the latest, and both follow the part`() {
        val p = product()
        val part = obj(p, TypeId.PART, "Bracket")
        val drawing = obj(p, TypeId.DOCUMENT, "Bracket drawing")
        val spec = obj(p, TypeId.DOCUMENT, "Coating spec")
        versioning.checkOut(alice, part)
        links.link(alice, part, DocumentLinkKind.DESCRIBED_BY, drawing.value)
        links.link(alice, part, DocumentLinkKind.REFERENCES, spec.value)
        assertThatThrownBy {
            links.link(alice, part, DocumentLinkKind.REFERENCES, spec.value)
        }.hasMessageContaining("already linked")
        assertThatThrownBy {
            links.link(alice, part, DocumentLinkKind.REFERENCES, part.value)
        }.hasMessageContaining("isn't a document")
        versioning.checkIn(alice, part)

        // The documents move on.
        versioning.checkOut(alice, drawing)
        versioning.checkIn(alice, drawing)
        versioning.checkOut(alice, spec)
        versioning.checkIn(alice, spec)
        val now = links.links(alice, part)
        val described = now.single { it.kind == DocumentLinkKind.DESCRIBED_BY }
        assertThat(described.document.version).isEqualTo("A.1")
        assertThat(described.newerExists).isTrue()
        assertThat(now.single { it.kind == DocumentLinkKind.REFERENCES }.document.version).isEqualTo("A.2")

        // Carried to the next iteration, where the described-by link can be brought up to date.
        versioning.checkOut(alice, part)
        val wc = links.links(alice, part, workingCopy = true)
        assertThat(wc).hasSize(2)
        assertThat(
            links
                .updateToLatest(
                    alice,
                    part,
                    wc
                        .single {
                            it.kind == DocumentLinkKind.DESCRIBED_BY
                        }.id,
                ).document.version,
        ).isEqualTo("A.2")
        links.unlink(alice, part, wc.single { it.kind == DocumentLinkKind.REFERENCES }.id)
        versioning.checkIn(alice, part)
        assertThat(links.links(alice, part).map { it.kind }).containsExactly(DocumentLinkKind.DESCRIBED_BY)

        // From the document's side.
        assertThat(links.linkingParts(alice, drawing).single().documentVersion).isEqualTo("A.2")
        assertThat(links.linkingParts(alice, spec)).isEmpty()
    }

    @Test
    fun `rules limit which documents a part type links to`() {
        val p = product()
        val bolts =
            types.createSubtype(
                admin,
                CreateSubtype(TypeId.PART, "Bolt${UUID.randomUUID().toString().take(6)}", "Bolt"),
            )
        val certs =
            types.createSubtype(
                admin,
                CreateSubtype(TypeId.DOCUMENT, "Cert${UUID.randomUUID().toString().take(6)}", "Certificate"),
            )
        assertThatThrownBy {
            links.addRule(alice, bolts.id.value, certs.id.value, DocumentLinkKind.REFERENCES)
        }.isInstanceOf(PlmException::class.java)
        val rule = links.addRule(admin, bolts.id.value, certs.id.value, DocumentLinkKind.REFERENCES)
        val bolt = obj(p, bolts.id, "M8 bolt")
        val cert = obj(p, certs.id, "Material certificate")
        val other = obj(p, TypeId.DOCUMENT, "Some document")
        versioning.checkOut(alice, bolt)
        links.link(alice, bolt, DocumentLinkKind.REFERENCES, cert.value)
        assertThatThrownBy {
            links.link(alice, bolt, DocumentLinkKind.REFERENCES, other.value)
        }.hasMessageContaining("can't be linked")
        assertThatThrownBy {
            links.link(alice, bolt, DocumentLinkKind.DESCRIBED_BY, cert.value)
        }.hasMessageContaining("can't be linked")
        links.removeRule(admin, rule.id)
        links.link(alice, bolt, DocumentLinkKind.DESCRIBED_BY, other.value)
    }
}
