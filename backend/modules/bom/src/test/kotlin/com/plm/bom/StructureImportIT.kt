package com.plm.bom

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.bom.api.AddUsage
import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ImportAction
import com.plm.bom.api.StructureImportService
import com.plm.jobs.api.JobService
import com.plm.jobs.api.JobStatus
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

/** Structure import from CSV and Excel files (roadmap 4.13). */
@PlmIntegrationTest
internal class StructureImportIT(
    @Autowired private val imports: StructureImportService,
    @Autowired private val bom: BomService,
    @Autowired private val jobs: JobService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Import ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
        }
    }

    private fun part(
        p: ContextId,
        name: String,
    ) = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, name))

    @Suppress("UNCHECKED_CAST")
    private fun run(
        root: MasterId,
        csv: String,
    ): List<Map<String, Any?>> {
        val rows = imports.read("bom.csv", csv.toByteArray())
        val job = imports.start(alice, root, rows, "bom.csv")
        var tries = 0
        while (jobs.get(alice, job.id).status in setOf(JobStatus.QUEUED, JobStatus.RUNNING) && tries++ < 200) {
            Thread.sleep(100)
        }
        val done = jobs.get(alice, job.id)
        assertThat(done.status).isEqualTo(JobStatus.SUCCEEDED)
        return done.result!!["results"] as List<Map<String, Any?>>
    }

    @Test
    fun `a file creates the missing parts and sets every assembly's structure, then updates it`() {
        val p = product()
        val top = part(p, "Drive unit")
        val bolt = part(p, "Bolt M8")
        val csv =
            """
            Level;Find number;Number;Name;Quantity;Unit;Reference designators
            1;1;;Motor assembly;1;each;
            2;1;${bolt.number};Bolt M8;4;each;B1-B4
            2;2;;Rotor;1;each;
            1;2;${bolt.number};Bolt M8;2,5;each;
            """.trimIndent()
        val preview = imports.preview(alice, top.id, imports.read("bom.csv", csv.toByteArray()))
        assertThat(preview.rows.map { it.action }).containsExactly(
            ImportAction.CREATE,
            ImportAction.MATCH,
            ImportAction.CREATE,
            ImportAction.MATCH,
        )
        // 2.5 bolts with no designators is fine; 2.5 with designators wouldn't be.
        assertThat(preview.errors).isZero()
        assertThat(preview.creates).isEqualTo(2)
        assertThat(preview.assemblies).isEqualTo(2)

        val results = run(top.id, csv)
        assertThat(results).allSatisfy { assertThat(it["ok"]).isEqualTo(true) }
        val tree = bom.structure(alice, top.id, ConfigSpec.LATEST, depth = 5)
        assertThat(tree.nodes.map { it.level to it.line.child.name }).containsExactly(
            1 to "Motor assembly",
            2 to "Bolt M8",
            2 to "Rotor",
            1 to "Bolt M8",
        )
        assertThat(tree.nodes[1].line.designators).containsExactly("B1", "B2", "B3", "B4")
        assertThat(tree.nodes[3].line.quantity).isEqualByComparingTo(BigDecimal("2.5"))

        // Again, with the motor assembly found by number: one line changed, one removed, one unchanged.
        val motor = tree.nodes[0].line.child
        val again =
            """
            Level,Number,Name,Quantity,Unit,Find number
            1,${motor.number},Motor assembly,1,each,1
            2,${bolt.number},Bolt M8,6,each,1
            """.trimIndent()
        val second = run(top.id, again).associate { it["number"] to it["message"].toString() }
        assertThat(second[motor.number]).contains("1 changed").contains("1 removed")
        assertThat(second[top.number]).contains("1 removed")
        assertThat(
            bom
                .structure(alice, top.id, ConfigSpec.LATEST, depth = 5)
                .nodes
                .map { it.line.child.name to it.line.quantity.toInt() },
        ).containsExactly("Motor assembly" to 1, "Bolt M8" to 6)
    }

    @Test
    fun `the preview finds what's wrong, line by line, and an import with errors can't start`() {
        val p = product()
        val top = part(p, "Frame")
        val released = part(p, "Released bracket")
        versioning.checkOut(alice, released.id)
        bom.add(alice, released.id, AddUsage(part(p, "Pin").id.value))
        versioning.checkIn(alice, released.id)
        versioning.setState(admin, released.id, SetState("RELEASED", override = true))
        val csv =
            """
            Level,Number,Name,Quantity,Unit,Reference designators
            1,NOPE-1,,1,each,
            3,,Floating,1,each,
            1,,Plate,-2,each,
            1,,Plate,2,parsec,
            1,${released.number},,1,each,
            2,,New pin,1,each,
            1,,Cap,2,each,C1
            1,${top.number},,1,each,
            """.trimIndent()
        val preview = imports.preview(alice, top.id, imports.read("bom.csv", csv.toByteArray()))
        val errors = preview.rows.map { it.errors.joinToString() }
        assertThat(errors[0]).contains("no part NOPE-1")
        assertThat(errors[1]).contains("Level jumps")
        assertThat(errors[2]).contains("above 0")
        assertThat(errors[3]).contains("isn't a unit")
        assertThat(errors[4]).contains("revise it first")
        assertThat(errors[6]).contains("1 reference designators for a quantity of 2")
        assertThat(errors[7]).contains("use itself")
        assertThatThrownBy { imports.start(alice, top.id, preview.rows.map { it.row }, "bom.csv") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `excel workbooks are read like csv files`() {
        // The smallest workbook: one sheet of inline strings and numbers.
        val sheet =
            """<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
<row r="1"><c r="A1" t="inlineStr"><is><t>Level</t></is></c><c r="B1" t="inlineStr"><is><t>Name</t></is></c><c r="D1" t="inlineStr"><is><t>Quantity</t></is></c></row>
<row r="2"><c r="A2"><v>1</v></c><c r="B2" t="inlineStr"><is><t>Washer</t></is></c><c r="D2"><v>3</v></c></row>
</sheetData></worksheet>"""
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray())
            zip.closeEntry()
        }
        val rows = imports.read("bom.xlsx", out.toByteArray())
        assertThat(rows.single().name).isEqualTo("Washer")
        assertThat(rows.single().quantity).isEqualByComparingTo("3")
        assertThat(rows.single().level).isEqualTo(1)
    }
}
