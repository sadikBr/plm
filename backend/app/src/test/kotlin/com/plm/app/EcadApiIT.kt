package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.io.File
import java.util.UUID

/** KiCad projects (roadmap 8.7), on KiCad's own pic_programmer demo. */
class EcadApiIT : ApiTest() {
    private val dir = File("../modules/ecad/src/test/resources/kicad")

    private fun send(path: String): Map<String, Any?> {
        val parts = LinkedMultiValueMap<String, Any>()
        for (f in dir.listFiles()!!.sortedBy { it.name }) {
            parts.add(
                "files",
                object : ByteArrayResource(f.readBytes()) {
                    override fun getFilename() = f.name
                },
            )
        }
        @Suppress("UNCHECKED_CAST")
        return RestClient
            .create("http://localhost:$port")
            .post()
            .uri(path)
            .header("Authorization", "Bearer ${TestTokens.token(TestTokens.alice)}")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(parts)
            .retrieve()
            .body(Map::class.java) as Map<String, Any?>
    }

    @Test
    fun `a KiCad project becomes documents, an assembly with its BOM, and checks clean`() {
        val s = UUID.randomUUID().toString().take(6)
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "ECAD $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Programmer $s"),
            ).body["id"]

        val preview = send("/api/v1/ecad/kicad/preview")
        assertThat(preview["project"]).isEqualTo("pic_programmer")
        assertThat((preview["sheets"] as List<*>).size).isEqualTo(2)
        assertThat(preview["footprints"] as Int).isGreaterThan(20)
        val lines = preview["lines"] as List<*>
        assertThat(lines.sumOf { ((it as Map<*, *>)["references"] as List<*>).size }).isEqualTo(56)

        val result = send("/api/v1/ecad/kicad/import?contextId=$product")
        val assembly = result["assembly"] as Map<*, *>
        assertThat((result["documents"] as List<*>).map { (it as Map<*, *>)["kind"] }).contains("SCHEMATIC", "BOARD")
        assertThat(result["lines"]).isEqualTo(lines.size)

        // The structure carries the designators as occurrences.
        val usages = get("/api/v1/objects/${assembly["id"]}/usages").list
        assertThat(usages.flatMap { (it["designators"] as List<*>) }).hasSize(56)
        val board = (result["documents"] as List<*>).map { it as Map<*, *> }.first { it["kind"] == "BOARD" }
        val files = get("/api/v1/objects/${board["id"]}/content").list
        assertThat(
            files.map { it["fileName"] },
        ).contains("pic_programmer.kicad_pcb", "pic_programmer board preview.png")

        // Straight after the import, the schematic and the structure agree; a line removed shows up.
        val project = result["project"] as Map<*, *>
        assertThat(get("/api/v1/objects/${project["id"]}/ecad/bom-check").body["differences"] as List<*>).isEmpty()
        post("/api/v1/objects/${assembly["id"]}/checkout")
        val line = get("/api/v1/objects/${assembly["id"]}/usages?workingCopy=true").list.first()
        val removed = call(HttpMethod.DELETE, "/api/v1/objects/${assembly["id"]}/usages/${line["id"]}")
        assertThat(removed.status).withFailMessage(removed.raw).isIn(200, 204)
        post("/api/v1/objects/${assembly["id"]}/checkin", mapOf("comment" to "oops"))
        val check = get("/api/v1/objects/${project["id"]}/ecad/bom-check").body
        assertThat((check["differences"] as List<*>).map { (it as Map<*, *>)["kind"] }).containsExactly("MISSING")
    }
}
