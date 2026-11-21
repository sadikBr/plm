package com.plm.classification

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.classification.api.ClassDataType
import com.plm.classification.api.ClassFilter
import com.plm.classification.api.ClassSearchService
import com.plm.classification.api.ClassificationService
import com.plm.classification.api.SaveClass
import com.plm.classification.api.SaveClassAttribute
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** Classification search and find similar (roadmap 7.12). */
@PlmIntegrationTest
internal class ClassSearchIT(
    @Autowired private val classes: ClassificationService,
    @Autowired private val search: ClassSearchService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("cs-bob-${UUID.randomUUID()}"))
    private val tag = UUID.randomUUID().toString().take(6)

    @Test
    fun `parts are found by class attributes, and by likeness`() {
        val motors = classes.createClass(admin, SaveClass("MOT-$tag", "Motors $tag"))
        classes.addAttribute(
            admin,
            motors.id,
            SaveClassAttribute("power", "Power", ClassDataType.NUMBER, "W", required = true),
        )
        classes.addAttribute(admin, motors.id, SaveClassAttribute("voltage", "Voltage", ClassDataType.NUMBER, "V"))
        classes.addAttribute(admin, motors.id, SaveClassAttribute("supplier", "Supplier", ClassDataType.TEXT))
        val bldc = classes.createClass(admin, SaveClass("BLDC-$tag", "BLDC", parentId = motors.id))
        classes.addAttribute(
            admin,
            bldc.id,
            SaveClassAttribute("cooling", "Cooling", ClassDataType.ENUM, allowed = listOf("passive", "fan", "liquid")),
        )
        val stepper = classes.createClass(admin, SaveClass("STP-$tag", "Steppers", parentId = motors.id))

        val org = contexts.create(admin, CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Cs $tag"))
        val lib = contexts.create(admin, CreateContext(org.id, ContextKind.LIBRARY, "Parts")).id
        val secret = contexts.create(admin, CreateContext(org.id, ContextKind.LIBRARY, "Secret")).id
        for (c in listOf(lib, secret)) teams.put(admin, c, alice.principal, TeamRole.MEMBER)
        teams.put(admin, lib, bob.principal, TeamRole.MEMBER)

        fun motor(
            name: String,
            cls: UUID,
            values: Map<String, Any?>,
            ctx: ContextId = lib,
        ): UUID {
            val id = versioning.create(alice, CreateVersionedObject(TypeId.PART, ctx, name)).id
            versioning.checkOut(alice, id)
            classes.classify(alice, id.value, cls, values)
            versioning.checkIn(alice, id)
            return id.value
        }
        val m1000 =
            motor(
                "M1000",
                bldc.id,
                mapOf(
                    "power" to 1000,
                    "voltage" to 48,
                    "cooling" to "fan",
                    "supplier" to "Kessler Antriebe",
                ),
            )
        motor(
            "M750",
            bldc.id,
            mapOf(
                "power" to 750,
                "voltage" to 48,
                "cooling" to "fan",
                "supplier" to "Kessler Antriebe",
            ),
        )
        motor("M1500", bldc.id, mapOf("power" to 1500, "voltage" to 48, "cooling" to "liquid"))
        motor("M400", bldc.id, mapOf("power" to 400, "voltage" to 24, "cooling" to "passive", "supplier" to "Nordmark"))
        motor("M950 secret", bldc.id, mapOf("power" to 950, "voltage" to 48, "cooling" to "fan"), secret)
        motor("Stepper 60", stepper.id, mapOf("power" to 60, "voltage" to 24))

        val names = { r: List<com.plm.classification.api.ClassifiedPart> -> r.map { it.name } }
        // A class finds its subclasses' parts too.
        assertThat(
            names(search.search(alice, motors.id).parts),
        ).containsExactlyInAnyOrder("M1000", "M1500", "M400", "M750", "M950 secret", "Stepper 60")
        assertThat(
            names(
                search
                    .search(
                        alice,
                        bldc.id,
                        listOf(ClassFilter("power", min = BigDecimal(700), max = BigDecimal(1200))),
                    ).parts,
            ),
        ).containsExactlyInAnyOrder("M1000", "M750", "M950 secret")
        assertThat(
            names(
                search
                    .search(
                        alice,
                        bldc.id,
                        listOf(ClassFilter("cooling", value = "fan"), ClassFilter("supplier", value = "kessler")),
                    ).parts,
            ),
        ).containsExactlyInAnyOrder("M1000", "M750")
        // Only what the reader may read.
        assertThat(
            names(search.search(bob, bldc.id, listOf(ClassFilter("power", min = BigDecimal(700)))).parts),
        ).containsExactlyInAnyOrder("M1000", "M1500", "M750")
        assertThatThrownBy { search.search(alice, motors.id, listOf(ClassFilter("cooling", value = "fan"))) }
            .hasMessageContaining("isn't an attribute of this class")
        assertThat(search.search(alice, motors.id, limit = 2).truncated).isTrue()

        // Like M1000: same class, nearest values first (M750 shares its supplier; M950 has none).
        val similar = search.similar(alice, m1000)
        assertThat(similar.similar.map { it.part.name }).containsExactly("M750", "M950 secret", "M1500", "M400")
        assertThat(similar.similar.first().score).isGreaterThan(similar.similar.last().score)
        assertThat(
            similar.similar
                .first()
                .matches
                .single { it.key == "cooling" }
                .score,
        ).isEqualTo(1.0)
        assertThat(search.similar(bob, m1000).similar.map { it.part.name }).doesNotContain("M950 secret")
    }
}
