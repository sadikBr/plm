package com.plm.classification

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.classification.api.ClassDataType
import com.plm.classification.api.ClassificationService
import com.plm.classification.api.SaveClass
import com.plm.classification.api.SaveClassAttribute
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** The class tree, inherited attributes, and classified parts (roadmap 7.11). */
@PlmIntegrationTest
internal class ClassificationIT(
    @Autowired private val classes: ClassificationService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val tag = UUID.randomUUID().toString().take(6)

    @Test
    fun `parts are classified with inherited attributes, kept per version`() {
        val fasteners = classes.createClass(admin, SaveClass("FST-$tag", "Fasteners $tag"))
        classes.addAttribute(
            admin,
            fasteners.id,
            SaveClassAttribute(
                "material",
                "Material",
                ClassDataType.ENUM,
                allowed = listOf("A2 stainless", "8.8 steel"),
            ),
        )
        val screws = classes.createClass(admin, SaveClass("SCR-$tag", "Screws", parentId = fasteners.id))
        classes.addAttribute(
            admin,
            screws.id,
            SaveClassAttribute("diameter", "Diameter", ClassDataType.NUMBER, "mm", required = true),
        )
        classes.addAttribute(
            admin,
            screws.id,
            SaveClassAttribute("length", "Length", ClassDataType.NUMBER, "mm", required = true),
        )
        val hex = classes.createClass(admin, SaveClass("HEX-$tag", "Hex screws", parentId = screws.id))
        classes.addAttribute(admin, hex.id, SaveClassAttribute("plated", "Zinc plated", ClassDataType.BOOLEAN))
        assertThat(
            classes.attributes(alice, hex.id).map { it.key },
        ).containsExactly("material", "diameter", "length", "plated")

        // Keys are unique along the tree; only administrators change it.
        assertThatThrownBy {
            classes.addAttribute(admin, hex.id, SaveClassAttribute("length", "Length again", ClassDataType.TEXT))
        }.hasMessageContaining("already an attribute of Screws")
        assertThatThrownBy {
            classes.createClass(alice, SaveClass("X-$tag", "Mine"))
        }.hasMessageContaining("site administrators")
        assertThatThrownBy {
            classes.updateClass(
                admin,
                fasteners.id,
                SaveClass("FST-$tag", "Fasteners", parentId = hex.id),
            )
        }.hasMessageContaining("under itself")

        val org = contexts.create(admin, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Cls $tag"))
        val lib = contexts.create(admin, CreateContext(org.id, ContextKind.LIBRARY, "Standard parts")).id
        teams.put(admin, lib, alice.principal, TeamRole.MEMBER)
        val bolt = versioning.create(alice, CreateVersionedObject(TypeId.PART, lib, "Hex screw M8x30")).id
        assertThatThrownBy {
            classes.classify(
                alice,
                bolt.value,
                hex.id,
                emptyMap(),
            )
        }.hasMessageContaining("not checked out")
        versioning.checkOut(alice, bolt)
        assertThatThrownBy {
            classes.classify(alice, bolt.value, hex.id, mapOf("diameter" to 8))
        }.hasMessageContaining("Length is required")
        assertThatThrownBy { classes.classify(alice, bolt.value, hex.id, mapOf("diameter" to "eight", "length" to 30)) }
            .hasMessageContaining("Diameter is a number")
        assertThatThrownBy {
            classes.classify(
                alice,
                bolt.value,
                hex.id,
                mapOf(
                    "diameter" to 8,
                    "length" to 30,
                    "material" to "brass",
                ),
            )
        }.hasMessageContaining("one of A2 stainless, 8.8 steel")
        val c =
            classes.classify(
                alice,
                bolt.value,
                hex.id,
                mapOf(
                    "diameter" to "8",
                    "length" to 30,
                    "material" to "8.8 steel",
                    "plated" to "yes",
                ),
            )
        assertThat(c.path.map { it.name }).containsExactly("Fasteners $tag", "Screws", "Hex screws")
        assertThat(c.values).containsEntry("plated", true)
        assertThat(BigDecimal(c.values["diameter"].toString())).isEqualByComparingTo("8")
        assertThat(classes.classification(alice, bolt.value).path).isEmpty()
        versioning.checkIn(alice, bolt)
        assertThat(classes.classification(alice, bolt.value).values).containsEntry("material", "8.8 steel")

        // Each version keeps its own: a revision starts with the previous values.
        versioning.setState(admin, bolt, SetState("RELEASED", override = true))
        versioning.revise(alice, bolt)
        assertThat(classes.classification(alice, bolt.value).version).startsWith("B.")
        assertThat(classes.classification(alice, bolt.value).values).containsEntry("plated", true)
        assertThat(classes.tree(alice).single { it.id == hex.id }.parts).isEqualTo(1)
        assertThatThrownBy { classes.deleteClass(admin, hex.id) }.hasMessageContaining("classified in it")
        assertThatThrownBy { classes.deleteClass(admin, screws.id) }.hasMessageContaining("classes under it")
    }
}
