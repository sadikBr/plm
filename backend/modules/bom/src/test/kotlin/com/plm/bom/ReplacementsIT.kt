package com.plm.bom

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddAlternate
import com.plm.bom.api.AddSubstitute
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ReplacementService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** Alternates and substitutes (roadmap 4.8). */
@PlmIntegrationTest
internal class ReplacementsIT(
    @Autowired private val replacements: ReplacementService,
    @Autowired private val bom: BomService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Repl ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
            teams.put(admin, it, bob.principal, TeamRole.VIEWER)
        }
    }

    private fun part(
        p: ContextId,
        name: String,
    ): MasterId = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name)).id

    @Test
    fun `alternates belong to the part, two-way if asked, and need the right to modify it`() {
        val p = product()
        val bolt = part(p, "M8 bolt steel")
        val alt = part(p, "M8 bolt stainless")
        val other = part(p, "M8 bolt titanium")
        replacements.addAlternate(alice, bolt, AddAlternate(alt.value, twoWay = true, notes = "Any plant"))
        replacements.addAlternate(alice, bolt, AddAlternate(other.value))
        assertThat(replacements.alternates(alice, bolt).map { it.part.name to it.twoWay })
            .containsExactlyInAnyOrder("M8 bolt stainless" to true, "M8 bolt titanium" to false)
        assertThat(
            replacements
                .alternates(alice, alt)
                .single()
                .part.id,
        ).isEqualTo(bolt.value)
        assertThatThrownBy {
            replacements.addAlternate(bob, bolt, AddAlternate(other.value))
        }.hasMessageContaining("permission")
        assertThatThrownBy {
            replacements.addAlternate(alice, bolt, AddAlternate(bolt.value))
        }.hasMessageContaining("its own alternate")
        replacements.removeAlternate(alice, bolt, alt.value)
        assertThat(replacements.alternates(alice, alt)).isEmpty()
    }

    @Test
    fun `substitutes belong to one line, follow it to the next iteration and show in the structure`() {
        val p = product()
        val board = part(p, "Board")
        val chip = part(p, "Chip A")
        val sub = part(p, "Chip B")
        versioning.checkOut(alice, board)
        val line = bom.add(alice, board, AddUsage(chip.value, BigDecimal("2")))
        replacements.addSubstitute(alice, board, line.id, AddSubstitute(sub.value, notes = "Shortage only"))
        assertThatThrownBy {
            replacements.addSubstitute(alice, board, line.id, AddSubstitute(chip.value))
        }.hasMessageContaining("itself")
        versioning.checkIn(alice, board)
        val checkedIn =
            bom
                .structure(alice, board, ConfigSpec.LATEST)
                .nodes
                .single()
                .line
        assertThat(checkedIn.substitutes).isEqualTo(1)
        assertThat(
            replacements
                .substitutes(alice, board, checkedIn.id)
                .single()
                .part.name,
        ).isEqualTo("Chip B")
        assertThat(
            replacements
                .substituteUses(alice, sub)
                .single()
                .original.name,
        ).isEqualTo("Chip A")
        // The next working copy keeps it; it can be removed there.
        versioning.checkOut(alice, board)
        val wcLine = bom.usages(alice, board, workingCopy = true).single()
        assertThat(wcLine.substitutes).isEqualTo(1)
        replacements.removeSubstitute(alice, board, wcLine.id, sub.value)
        versioning.checkIn(alice, board)
        assertThat(replacements.substituteUses(alice, sub)).isEmpty()
    }
}
