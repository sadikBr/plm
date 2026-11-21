package com.plm.cad

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.BomService
import com.plm.cad.api.CadService
import com.plm.cad.api.StepImportOptions
import com.plm.cad.api.StepImportService
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.versioning.api.MasterId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** STEP import (roadmap 6.5), with the CAx-IF AS1 assembly. */
@PlmIntegrationTest
internal class StepImportIT(
    @Autowired private val imports: StepImportService,
    @Autowired private val cad: CadService,
    @Autowired private val bom: BomService,
    @Autowired private val content: ContentService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val as1 = javaClass.getResource("/step/as1_pe_203.stp")!!.readBytes()

    @Test
    fun `a STEP assembly becomes models with their structure, and parts built from them`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "STEP ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)

        val preview = imports.preview(alice, "as1_pe_203.stp", as1)
        assertThat(preview.schema).isEqualTo("STEP AP203")
        assertThat(preview.models).isEqualTo(9)
        assertThat(preview.assemblies).isEqualTo(4)
        assertThat(preview.lines.first().name).isEqualTo("AS1_PE_ASM")

        val result = imports.import(alice, "as1_pe_203.stp", as1, StepImportOptions(p.value))
        assertThat(result.models).hasSize(9)
        assertThat(result.parts).hasSize(9)
        val tree = cad.tree(alice, result.rootId)
        assertThat(tree.first().model.name).isEqualTo("AS1_PE_ASM")
        assertThat(tree.count { it.model.name == "BOLT" }).isEqualTo(6) // 3 per bracket, 2 brackets
        assertThat(tree.count { it.model.name == "NUT" }).isEqualTo(8)
        val rod = tree.first { it.model.name == "ROD_ASM" }
        assertThat(rod.worldTransform!!.subList(12, 15)).containsExactly(50.0, 40.0, 5.62883073485E-14)
        assertThat(content.list(alice, MasterId(result.rootId)).single().fileName).isEqualTo("as1_pe_203.stp")

        // The top part's structure was built from its model.
        val top = result.parts.first { it.name == "AS1_PE_ASM" }
        assertThat(bom.usages(alice, MasterId(top.id)).associate { it.child.name to it.quantity.toInt() })
            .isEqualTo(mapOf("PLATE" to 1, "L_BRACKET_ASSEMBLY_ASM" to 2, "ROD_ASM" to 1))

        assertThatThrownBy { imports.preview(alice, "notes.txt", "hello".toByteArray()) }.hasMessageContaining(".stp")
        assertThatThrownBy {
            imports.preview(
                alice,
                "bad.stp",
                "hello".toByteArray(),
            )
        }.hasMessageContaining("STEP file")
    }
}
