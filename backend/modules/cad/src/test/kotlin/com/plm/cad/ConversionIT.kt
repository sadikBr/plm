package com.plm.cad

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.cad.api.ConversionService
import com.plm.cad.api.ConversionStatus
import com.plm.cad.api.StepImportOptions
import com.plm.cad.api.StepImportService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** STEP to glTF conversion (roadmap 6.6). Needs node and workers/cad-converter installed. */
@PlmIntegrationTest
internal class ConversionIT(
    @Autowired private val conversions: ConversionService,
    @Autowired private val imports: StepImportService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun converterInstalled(): Boolean {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("workers/cad-converter/node_modules"))) return true
            dir = dir.parent
        }
        return false
    }

    private fun waitReady(id: UUID): com.plm.cad.api.Viewable {
        repeat(240) {
            val v = conversions.viewable(alice, id)
            if (v.status == ConversionStatus.READY || v.status == ConversionStatus.FAILED) return v
            Thread.sleep(250)
        }
        return conversions.viewable(alice, id)
    }

    @Test
    fun `a checked-in STEP model is converted, and kept for versions with the same file`() {
        assumeTrue(converterInstalled(), "workers/cad-converter isn't installed")
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Convert ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val as1 = javaClass.getResource("/step/as1_pe_203.stp")!!.readBytes()
        val root = imports.import(alice, "as1_pe_203.stp", as1, StepImportOptions(p.value, createParts = false)).rootId

        val v = waitReady(root)
        assertThat(v.error).isNull()
        assertThat(v.status).isEqualTo(ConversionStatus.READY)
        assertThat(v.meshes).isEqualTo(18)
        assertThat(v.url).contains("viewable.glb")
        // Models without a file have nothing to show.
        val part = imports.preview(alice, "as1_pe_203.stp", as1)
        assertThat(part.models).isEqualTo(9)

        // A new version with the same file has its viewable at once.
        versioning.checkOut(alice, MasterId(root))
        versioning.checkIn(alice, MasterId(root), "No change to the file")
        val again = conversions.viewable(alice, root)
        assertThat(again.version).isEqualTo("A.3")
        assertThat(again.status).isEqualTo(ConversionStatus.READY)
    }
}
