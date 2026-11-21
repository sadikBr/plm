package com.plm.versioning

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeSettings
import com.plm.types.api.CreateSubtype
import com.plm.types.api.DataType
import com.plm.types.api.DefineAttribute
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.Rename
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Reference and alias attributes (roadmap 4.12). */
@PlmIntegrationTest
internal class ReferenceAttributesIT(
    @Autowired private val versioning: VersioningService,
    @Autowired private val organizing: OrganizingService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val types: TypeService,
    @Autowired private val attributes: AttributeService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))

    private fun product(member: Actor = alice): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Refs ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, member.principal, TeamRole.MEMBER)
        }
    }

    /** A part subtype pointing to a specification document, with two aliases of it. */
    private fun specifiedPart(): TypeId {
        val type =
            types
                .createSubtype(
                    admin,
                    CreateSubtype(TypeId.PART, "Spec${UUID.randomUUID().toString().take(8)}", "Specified"),
                ).id

        fun define(
            name: String,
            dataType: DataType,
            aliasOf: String? = null,
            referenceType: TypeId? = null,
        ) = attributes.define(
            admin,
            DefineAttribute(
                type,
                name,
                dataType,
                settings = AttributeSettings(mapOf("en" to name), aliasOf = aliasOf),
                referenceTypeId = referenceType,
            ),
        )
        define("specification", DataType.REFERENCE, referenceType = TypeId.DOCUMENT)
        define("specNumber", DataType.STRING, aliasOf = "specification.number")
        define("specState", DataType.STRING, aliasOf = "specification.state")
        return type
    }

    @Test
    fun `a reference points to an object of its type, and aliases show that object's values live`() {
        val p = product()
        val type = specifiedPart()
        val spec = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, p, "Bracket specification"))
        val part =
            versioning.create(
                alice,
                CreateVersionedObject(
                    type,
                    p,
                    "Bracket",
                    attributes = mapOf("specification" to spec.id.value.toString()),
                ),
            )
        val read = versioning.get(alice, part.id).latest.attributes
        assertThat(read["specification"]).isEqualTo(spec.id.value.toString())
        assertThat(read["specNumber"]).isEqualTo(spec.number)
        assertThat(read["specState"]).isEqualTo("IN_WORK")

        // The alias follows the referenced object, without touching the part.
        versioning.rename(admin, spec.id, Rename(number = "SPEC-${UUID.randomUUID().toString().take(6)}"))
        val renamed = versioning.get(alice, spec.id).number
        assertThat(versioning.get(alice, part.id).latest.attributes["specNumber"]).isEqualTo(renamed)
        assertThat(
            versioning
                .list(alice, ObjectQuery(ids = listOf(part.id)))
                .single()
                .latest.attributes["specNumber"],
        ).isEqualTo(renamed)

        // And the document knows who points to it.
        assertThat(versioning.referencedBy(alice, spec.id).map { it.number to it.attribute })
            .containsExactly(part.number to "specification")
    }

    @Test
    fun `references must exist, be readable and have the right type, and aliases can't be set`() {
        val p = product()
        val type = specifiedPart()
        val otherPart = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Not a document"))
        assertThatThrownBy {
            versioning.create(
                alice,
                CreateVersionedObject(
                    type,
                    p,
                    "Wrong",
                    attributes =
                        mapOf(
                            "specification" to otherPart.id.value.toString(),
                        ),
                ),
            )
        }.isInstanceOf(ValidationException::class.java).hasMessageContaining("must point to a")
        assertThatThrownBy {
            versioning.create(
                alice,
                CreateVersionedObject(
                    type,
                    p,
                    "Ghost",
                    attributes =
                        mapOf(
                            "specification" to UUID.randomUUID().toString(),
                        ),
                ),
            )
        }.isInstanceOf(ValidationException::class.java).hasMessageContaining("doesn't exist")

        // A document bob can't see is, for him, as good as missing; and he can't read it through an alias either.
        val hidden = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, p, "Secret spec"))
        val bobs = product(bob)
        assertThatThrownBy {
            versioning.create(
                bob,
                CreateVersionedObject(
                    type,
                    bobs,
                    "Peek",
                    attributes =
                        mapOf(
                            "specification" to hidden.id.value.toString(),
                        ),
                ),
            )
        }.isInstanceOf(ValidationException::class.java)

        val spec = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, p, "Real spec"))
        val part =
            versioning.create(
                alice,
                CreateVersionedObject(
                    type,
                    p,
                    "Sets an alias",
                    attributes = mapOf("specification" to spec.id.value.toString(), "specNumber" to "MADE-UP"),
                ),
            )
        assertThat(versioning.get(alice, part.id).latest.attributes["specNumber"]).isEqualTo(spec.number)
        // Never stored: only resolved on read.
        assertThat(part.latest.attributes).doesNotContainKey("specNumber")
    }

    @Test
    fun `a referenced object can't be deleted, and alias definitions must name a reference`() {
        val p = product()
        val type = specifiedPart()
        val spec = versioning.create(alice, CreateVersionedObject(TypeId.DOCUMENT, p, "Kept spec"))
        versioning.create(
            alice,
            CreateVersionedObject(type, p, "Holder", attributes = mapOf("specification" to spec.id.value.toString())),
        )
        assertThatThrownBy { organizing.delete(admin, MasterId(spec.id.value)) }
            .isInstanceOf(PlmException::class.java)
            .hasMessageContaining("referenced by")

        assertThatThrownBy {
            attributes.define(
                admin,
                DefineAttribute(
                    type,
                    "broken",
                    DataType.STRING,
                    settings = AttributeSettings(mapOf("en" to "Broken"), aliasOf = "specNumber.name"),
                ),
            )
        }.isInstanceOf(ValidationException::class.java).hasMessageContaining("REFERENCE")
    }
}
