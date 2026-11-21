package com.plm.search.domain

import com.plm.platform.error.ValidationException
import com.plm.search.api.SheetExport
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal Office Open XML workbook (roadmap 3.7): one sheet, a bold frozen header row, text and
 * numbers. Enough for Excel, LibreOffice and Google Sheets, without a spreadsheet library.
 */
internal object Xlsx {
    private const val MAX_ROWS = 50_000

    fun write(sheet: SheetExport): ByteArray {
        if (sheet.rows.size >
            MAX_ROWS
        ) {
            throw ValidationException("EXPORT_TOO_LARGE", "At most $MAX_ROWS rows can be exported")
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(
                name: String,
                xml: String,
            ) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""",
            )
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""",
            )
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${escape(sheetName(sheet.sheetName))}" sheetId="1" r:id="rId1"/></sheets>
</workbook>""",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""",
            )
            put(
                "xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf/></cellStyleXfs>
<cellXfs count="2"><xf fontId="0"/><xf fontId="1" applyFont="1"/></cellXfs>
</styleSheet>""",
            )
            val widths =
                sheet.columns.indices.map { i ->
                    val longest =
                        (
                            listOf(sheet.columns[i]) +
                                sheet.rows.take(500).map { it.getOrNull(i)?.toString().orEmpty() }
                        ).maxOf { it.length }
                    longest.coerceIn(8, 60) + 2
                }
            val rows =
                buildString {
                    append(row(1, sheet.columns, header = true))
                    sheet.rows.forEachIndexed { i, r -> append(row(i + 2, r, header = false)) }
                }
            put(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<cols>${widths.mapIndexed {
                    i,
                    w,
                    ->
                    """<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>"""
                }.joinToString("")}</cols>
<sheetData>$rows</sheetData>
<autoFilter ref="A1:${column(sheet.columns.size - 1)}${sheet.rows.size + 1}"/>
</worksheet>""",
            )
        }
        return out.toByteArray()
    }

    private fun row(
        n: Int,
        cells: List<Any?>,
        header: Boolean,
    ): String =
        buildString {
            append("<row r=\"$n\">")
            cells.forEachIndexed { i, v ->
                val ref = "${column(i)}$n"
                val style = if (header) " s=\"1\"" else ""
                when (v) {
                    null, "" -> Unit
                    is Number -> append("<c r=\"$ref\"$style><v>$v</v></c>")
                    is Boolean -> append("<c r=\"$ref\" t=\"b\"$style><v>${if (v) 1 else 0}</v></c>")
                    else ->
                        append(
                            "<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${escape(
                                v.toString(),
                            )}</t></is></c>",
                        )
                }
            }
            append("</row>")
        }

    /** 0 → A, 25 → Z, 26 → AA. */
    fun column(index: Int): String {
        var i = index
        val s = StringBuilder()
        do {
            s.insert(0, 'A' + i % 26)
            i = i / 26 - 1
        } while (i >= 0)
        return s.toString()
    }

    private fun sheetName(name: String) = name.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31).ifBlank { "Sheet1" }

    private fun escape(s: String) =
        s
            .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
