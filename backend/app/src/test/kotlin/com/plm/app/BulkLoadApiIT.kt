package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bulk load (roadmap 9.2): a dry run that explains each row, then the load as a job. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkLoadApiIT : ApiTest() {
    private val s = UUID.randomUUID().toString().take(6)
    private lateinit var product: String
    private val json = JsonMapper.builder().build()

    @BeforeAll
    fun data() {
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Load $s"),
            ).body["id"]
        product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Load $s"),
            ).body["id"] as String
    }

    private fun named(
        name: String,
        bytes: ByteArray,
    ) = object : ByteArrayResource(bytes) {
        override fun getFilename() = name
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun send(
        path: String,
        csv: String,
        archive: ByteArray? = null,
    ): Pair<Int, Map<String, Any?>> {
        val parts = LinkedMultiValueMap<String, Any>()
        parts.add("sheet", named("chassis.csv", csv.toByteArray()))
        if (archive != null) parts.add("files", named("files.zip", archive))
        return RestClient
            .create("http://localhost:$port")
            .post()
            .uri("$path?contextId=$product")
            .header("Authorization", "Bearer ${TestTokens.token(TestTokens.alice)}")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(parts)
            .exchange { _, r ->
                r.statusCode.value() to
                    json.readValue(r.body.readAllBytes(), Map::class.java) as Map<String, Any?>
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(preview: Map<String, Any?>) = preview["rows"] as List<Map<String, Any?>>

    private val header = "Type,Number,Name,Folder,File,Parent,Quantity,Find number,Documents,Description,Source"

    @Test
    fun `the dry run explains every row, and nothing loads while any row is wrong`() {
        val csv =
            """
            $header
            Part,X1-$s,Bracket,,,,,,,Bent sheet,Make
            Gizmo,X2-$s,Thing,,,,,,,,
            Part,X3-$s,Bolt,,,NOPE-$s,2,,,,Maybe
            Part,X1-$s,Bracket again,,,,,,,,
            Part,X4-$s,Plate,,plate.pdf,,,,,,
            """.trimIndent()
        val (_, preview) = send("/api/v1/bulk-loads/preview", csv)
        val r = rows(preview)
        assertThat(r[0]["action"]).isEqualTo("CREATE")
        assertThat(r[1]["errors"].toString()).contains("There is no type 'Gizmo'")
        assertThat(r[2]["errors"].toString()).contains("source: 'Maybe'").contains("There is no part NOPE-$s")
        assertThat(r[3]["errors"].toString()).contains("already on row 2")
        assertThat(r[4]["errors"].toString()).contains("Add the zip with plate.pdf")
        assertThat(preview["errors"]).isEqualTo(4)

        val (status, refused) = send("/api/v1/bulk-loads", csv)
        assertThat(status).isEqualTo(422)
        assertThat(refused["code"]).isEqualTo("INVALID_LOAD")
    }

    @Test
    fun `a load creates objects in folders, with files, structure and document links`() {
        val csv =
            """
            $header
            Part,BL-A-$s,Frame assembly,/Chassis/Frames,,,,,BL-D-$s,Welded frame,Make
            Part,BL-B-$s,Side rail,/Chassis/Frames,,BL-A-$s,2,10,,,Buy
            Part,BL-C-$s,Cross member,/Chassis/Frames,,BL-A-$s,3,20,,,
            Document,BL-D-$s,Frame drawing,/Chassis/Drawings,drawings/frame.pdf,,,,,,
            """.trimIndent()
        val files = zip("drawings/frame.pdf" to "%PDF-1.4 frame".toByteArray(), "__MACOSX/._frame.pdf" to ByteArray(3))
        val (_, preview) = send("/api/v1/bulk-loads/preview", csv, files)
        assertThat(preview["errors"]).withFailMessage(preview.toString()).isEqualTo(0)
        assertThat(preview["creates"]).isEqualTo(4)
        assertThat(preview["usages"]).isEqualTo(2)
        assertThat(preview["links"]).isEqualTo(1)
        assertThat(preview["newFolders"] as List<*>).contains("/Chassis", "/Chassis/Frames", "/Chassis/Drawings")

        val (status, job) = send("/api/v1/bulk-loads", csv, files)
        assertThat(status).withFailMessage(job.toString()).isEqualTo(202)
        var done = job
        repeat(120) {
            if (done["status"] in setOf("SUCCEEDED", "FAILED")) return@repeat
            Thread.sleep(250)
            done = get("/api/v1/jobs/${job["id"]}").body
        }
        assertThat(done["status"]).withFailMessage(done.toString()).isEqualTo("SUCCEEDED")
        val result = done["result"] as Map<*, *>
        assertThat(result["failed"]).withFailMessage(result.toString()).isEqualTo(0)

        val assembly =
            (result["results"] as List<*>).map { it as Map<*, *> }.first {
                it["number"] == "BL-A-$s" &&
                    it["row"] == null
            }
        assertThat(assembly["message"].toString()).contains("2 structure lines added", "1 document linked")
        val a = assembly["objectId"]
        val lines = get("/api/v1/objects/$a/usages").list
        assertThat(lines.map { (it["child"] as Map<*, *>)["number"] }).containsExactlyInAnyOrder("BL-B-$s", "BL-C-$s")
        assertThat(
            lines.first { (it["child"] as Map<*, *>)["number"] == "BL-C-$s" }["quantity"].toString(),
        ).startsWith("3")
        val attrs = get("/api/v1/objects/$a").body["latest"] as Map<*, *>
        assertThat((attrs["attributes"] as Map<*, *>)["source"]).isEqualTo("make")

        // Loading the same sheet again changes nothing new: rows match their objects.
        val (_, again) = send("/api/v1/bulk-loads/preview", csv, files)
        assertThat(again["creates"]).isEqualTo(0)
    }

    @Test
    fun `a template lists the type's attributes`() {
        val csv =
            RestClient
                .create("http://localhost:$port")
                .get()
                .uri("/api/v1/bulk-loads/template?typeId=00000000-0000-7000-8000-000000000102")
                .header("Authorization", "Bearer ${TestTokens.token(TestTokens.alice)}")
                .retrieve()
                .body(String::class.java)!!
        assertThat(csv.lines().first()).startsWith("Type,Number,Name,Folder,File,Parent").contains("Description")
    }
}
