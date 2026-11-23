package com.plm.office.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A minimal Word file, as Word writes it (core properties, no custom ones). */
internal fun docx(
    title: String,
    custom: Map<String, String> = emptyMap(),
): ByteArray {
    val parts =
        linkedMapOf(
            "[Content_Types].xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""",
            "_rels/.rels" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""",
            "word/document.xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Bracket test procedure</w:t></w:r></w:p></w:body></w:document>""",
            "docProps/core.xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>Alice</dc:creator><cp:keywords>bracket, test</cp:keywords></cp:coreProperties>""",
        )
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, xml) in parts) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
    }
    return if (custom.isEmpty()) bytes.toByteArray() else OfficeProperties.write(bytes.toByteArray(), custom)
}

class OfficePropertiesTest {
    private fun names(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                out += e.name
                e = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun `reads core properties`() {
        val props = OfficeProperties.read(docx("Bracket test"))
        assertThat(
            props,
        ).containsEntry(
            "title",
            "Bracket test",
        ).containsEntry("author", "Alice")
            .containsEntry("keywords", "bracket, test")
    }

    @Test
    fun `writes core and custom properties, registering the custom part`() {
        val written =
            OfficeProperties.write(
                docx("Old"),
                mapOf(
                    "title" to "New title",
                    "category" to "Procedure",
                    "Department" to "Quality",
                ),
            )
        val props = OfficeProperties.read(written)
        assertThat(props)
            .containsEntry("title", "New title")
            .containsEntry("category", "Procedure")
            .containsEntry("Department", "Quality")
            .containsEntry("author", "Alice")
        assertThat(names(written)).contains("docProps/custom.xml", "word/document.xml")
        val types =
            String(
                ZipInputStream(ByteArrayInputStream(written)).use { z ->
                    generateSequence { z.nextEntry }
                        .first {
                            it.name ==
                                "[Content_Types].xml"
                        }.let { z.readAllBytes() }
                },
            )
        assertThat(types).contains("/docProps/custom.xml")
        // Writing again updates, never duplicates.
        val again = OfficeProperties.write(written, mapOf("Department" to "Engineering"))
        assertThat(OfficeProperties.read(again)).containsEntry("Department", "Engineering")
        assertThat(
            String(
                ZipInputStream(ByteArrayInputStream(again)).use { z ->
                    generateSequence { z.nextEntry }
                        .first {
                            it.name ==
                                "docProps/custom.xml"
                        }.let { z.readAllBytes() }
                },
            ).split("name=\"Department\""),
        ).hasSize(2)
    }

    @Test
    fun `anything that isn't an Office file has no properties`() {
        assertThat(OfficeProperties.read("plain text".toByteArray())).isEmpty()
        assertThat(OfficeProperties.isOffice("Spec.DOCX")).isTrue()
        assertThat(OfficeProperties.isOffice("model.step")).isFalse()
    }
}
