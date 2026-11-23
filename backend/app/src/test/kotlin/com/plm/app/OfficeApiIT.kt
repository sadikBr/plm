package com.plm.app

import com.plm.app.support.ApiTest
import com.plm.app.support.TestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Office files (roadmap 8.4): properties fill attributes on attach, and go back into downloads. */
class OfficeApiIT : ApiTest() {
    private val document = "00000000-0000-7000-8000-000000000101"

    private fun docx(): ByteArray {
        val parts =
            mapOf(
                "[Content_Types].xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><Types """ +
                    """xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="rels" """ +
                    """ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                    """<Default Extension="xml" ContentType="application/xml"/></Types>""",
                "_rels/.rels" to
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships """ +
                    """xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" """ +
                    """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" """ +
                    """""" +
                    """Target="word/document.xml"/></Relationships>""",
                "word/document.xml" to
                    """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>""",
                "docProps/core.xml" to
                    """<cp:coreProperties """ +
                    """xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
                    """xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Bracket test procedure</dc:title>""" +
                    """<dc:description>How the bracket is load tested.</dc:description></cp:coreProperties>""",
                "docProps/custom.xml" to
                    """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" """ +
                    """xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">""" +
                    """<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="Department">""" +
                    """<vt:lpwstr>Quality</vt:lpwstr>""" +
                    """</property></Properties>""",
            )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, xml) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun part(
        bytes: ByteArray,
        name: String,
    ): String =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == name }.let { String(zip.readAllBytes()) }
        }

    private fun upload(bytes: ByteArray): String {
        val ticket = post("/api/v1/vault/uploads", mapOf("fileName" to "Procedure.docx", "size" to bytes.size))
        RestClient
            .create("http://localhost:$port")
            .put()
            .uri(ticket.body["uploadUrl"] as String)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
            .retrieve()
            .toBodilessEntity()
        return ticket.body["uploadId"] as String
    }

    @Test
    fun `an Office file's properties fill the document, and its attributes go back into the file`() {
        val s = UUID.randomUUID().toString().take(6)
        val site = get("/api/v1/contexts/site").body["id"]
        val org =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to site, "kind" to "ORGANIZATION", "name" to "Office $s"),
            ).body["id"]
        val product =
            post(
                "/api/v1/contexts",
                mapOf("parentId" to org, "kind" to "PRODUCT", "name" to "Office $s"),
            ).body["id"]
        val id =
            post(
                "/api/v1/objects",
                mapOf("typeId" to document, "contextId" to product, "name" to "Procedure $s"),
            ).body["id"]
        post("/api/v1/objects/$id/checkout")
        val attached = post("/api/v1/objects/$id/content", mapOf("uploadId" to upload(docx()), "role" to "PRIMARY"))
        assertThat(attached.status).withFailMessage(attached.raw).isEqualTo(201)
        val contentId = attached.body["id"]

        // Title, description and the custom Department (matched to its value list by label) filled.
        val wc = get("/api/v1/objects/$id/working-copy").body["attributes"] as Map<*, *>
        assertThat(wc["title"]).isEqualTo("Bracket test procedure")
        assertThat(wc["description"]).isEqualTo("How the bracket is load tested.")
        assertThat(wc["department"]).isEqualTo("quality")

        // Changed in the system, the download carries the new values.
        call(
            HttpMethod.PATCH,
            "/api/v1/objects/$id/working-copy",
            body =
                mapOf(
                    "attributes" to wc + mapOf("title" to "Bracket load test"),
                ),
        )
        val file =
            RestClient
                .create("http://localhost:$port")
                .get()
                .uri("/api/v1/office/content/$contentId")
                .header("Authorization", "Bearer ${TestTokens.token(TestTokens.alice)}")
                .retrieve()
                .body(ByteArray::class.java)!!
        assertThat(part(file, "docProps/core.xml")).contains("Bracket load test")
        assertThat(part(file, "docProps/custom.xml")).contains("Quality")

        // Mappings: inherited by subtypes, changed only by administrators.
        val mappings = get("/api/v1/office/mappings/$document").list
        assertThat(mappings.map { it["property"] }).contains("title", "Department")
        val refused =
            put(
                "/api/v1/office/mappings/$document",
                listOf(
                    mapOf(
                        "property" to "title",
                        "attribute" to "title",
                    ),
                ),
                TestTokens.bob,
            )
        assertThat(refused.body["code"]).isEqualTo("ADMIN_REQUIRED")
        val bad = put("/api/v1/office/mappings/$document", listOf(mapOf("property" to "title", "attribute" to "nope")))
        assertThat(bad.body["code"]).isEqualTo("UNKNOWN_ATTRIBUTE")
    }
}
