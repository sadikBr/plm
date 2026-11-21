package com.plm.bom.domain

import com.plm.bom.api.ImportRow
import com.plm.platform.error.ValidationException
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val MAX_ROWS = 5_000
private const val MAX_BYTES = 10 * 1024 * 1024

/** Header titles each column is recognized by, lower case (roadmap 4.13). */
private val COLUMNS =
    mapOf(
        "level" to setOf("level", "niveau", "ebene"),
        "number" to setOf("number", "part number", "numéro", "nummer"),
        "name" to setOf("name", "nom"),
        "quantity" to setOf("quantity", "qty", "quantité", "menge"),
        "unit" to setOf("unit", "unité", "einheit"),
        "findNumber" to setOf("find number", "find no.", "find", "repère", "pos.-nr.", "positionsnummer"),
        "designators" to setOf("reference designators", "designators", "repères topologiques", "referenzbezeichner"),
        "notes" to setOf("notes", "note", "remarques", "notizen", "bemerkungen"),
    )

/** Reads structure files: CSV (comma or semicolon separated) and Excel workbooks (first sheet). */
internal object Sheets {
    fun read(
        fileName: String,
        bytes: ByteArray,
    ): List<ImportRow> {
        if (bytes.size > MAX_BYTES) bad("The file is larger than 10 MB")
        val table =
            when {
                fileName.lowercase().endsWith(".xlsx") -> xlsx(bytes)
                fileName.lowercase().endsWith(
                    ".csv",
                ) ||
                    fileName.lowercase().endsWith(".txt") -> csv(String(bytes, Charsets.UTF_8))
                else -> bad("Use a .csv or .xlsx file")
            }.dropWhile { row -> row.all { it.isBlank() } }
        if (table.isEmpty()) bad("The file is empty")
        val header = table.first().map { it.trim().lowercase() }
        val index =
            COLUMNS.mapValues { (_, titles) -> header.indexOfFirst { it in titles } }.filterValues { it >= 0 }
        if ("level" !in index) bad("The file needs a Level column")
        if ("number" !in index && "name" !in index) bad("The file needs a Number or a Name column")
        val rows =
            table.drop(1).mapIndexedNotNull { i, cells ->
                if (cells.all { it.isBlank() }) return@mapIndexedNotNull null

                fun cell(key: String) = index[key]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
                val row = i + 2
                ImportRow(
                    row = row,
                    level = cell("level")?.toBigDecimalOrNull()?.toInt() ?: -1,
                    number = cell("number"),
                    name = cell("name"),
                    quantity =
                        cell("quantity")?.let(::decimal) ?: if (cell("quantity") == null) null else BigDecimal(-1),
                    unit = cell("unit"),
                    findNumber = cell("findNumber"),
                    designators = cell("designators"),
                    notes = cell("notes"),
                )
            }
        if (rows.size > MAX_ROWS) bad("At most $MAX_ROWS lines can be imported at once")
        return rows
    }

    /** 1.5, 1,5 (decimal comma) and 1 000 all work; anything else is null. */
    private fun decimal(text: String): BigDecimal? {
        val t = text.replace(" ", "").replace(" ", "")
        return (
            if (t.contains(',') &&
                !t.contains('.')
            ) {
                t.replace(',', '.')
            } else {
                t.replace(",", "")
            }
        ).toBigDecimalOrNull()
    }

    private fun bad(message: String): Nothing = throw ValidationException("INVALID_IMPORT_FILE", message)

    fun csv(text: String): List<List<String>> {
        val body = text.removePrefix("")
        val firstLine = body.lineSequence().firstOrNull().orEmpty()
        val separator = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                quoted && c == '"' && body.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == separator -> {
                    row += cell.toString()
                    cell.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && body.getOrNull(i + 1) == '\n') i++
                    row += cell.toString()
                    cell.clear()
                    rows += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }

    /** The first worksheet's cells as text: shared strings, inline strings and numbers. */
    fun xlsx(bytes: ByteArray): List<List<String>> {
        val parts = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0L
                generateSequence { zip.nextEntry }.forEach { e ->
                    if (e.name == "xl/sharedStrings.xml" || e.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) {
                        val data = zip.readNBytes(MAX_BYTES * 5)
                        total += data.size
                        if (total > MAX_BYTES * 5L) bad("The workbook is too large")
                        parts[e.name] = data
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            bad("This isn't an Excel workbook")
        }
        val sheet =
            parts.keys.filter { it.startsWith("xl/worksheets/") }.minByOrNull { it.filter(Char::isDigit).toInt() }
                ?: bad("The workbook has no sheet")
        val shared =
            parts["xl/sharedStrings.xml"]?.let { xml ->
                val items = parse(xml).getElementsByTagNameNS("*", "si")
                (0 until items.length).map { (items.item(it) as Element).textContent }
            } ?: emptyList()
        val rows = parse(parts.getValue(sheet)).getElementsByTagNameNS("*", "row")
        return (0 until rows.length).map { r ->
            val cells = (rows.item(r) as Element).getElementsByTagNameNS("*", "c")
            val out = mutableListOf<String>()
            for (c in 0 until cells.length) {
                val cell = cells.item(c) as Element
                val col = column(cell.getAttribute("r")) ?: out.size
                while (out.size < col) out += ""
                val value =
                    when (cell.getAttribute("t")) {
                        "s" -> shared.getOrElse(text(cell, "v").toIntOrNull() ?: -1) { "" }
                        "inlineStr" -> text(cell, "is")
                        else ->
                            text(cell, "v").let { v ->
                                v.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
                                    ?: v
                            }
                    }
                if (col < out.size) out[col] = value else out += value
            }
            out
        }
    }

    private fun text(
        cell: Element,
        tag: String,
    ): String = cell.getElementsByTagNameNS("*", tag).item(0)?.textContent ?: ""

    /** A1 → 0, C7 → 2; null without a reference (then it's the next column). */
    private fun column(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty() || letters.length > 3) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }

    /** No DTDs or external entities: the file comes from a user. */
    private fun parse(xml: ByteArray) =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml))
}
