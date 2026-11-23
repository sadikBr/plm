package com.plm.bom.domain

import com.plm.bom.api.ImportRow
import com.plm.platform.error.ValidationException
import com.plm.platform.sheets.Tables
import java.math.BigDecimal

private const val MAX_ROWS = 5_000

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
        val table = Tables.read(fileName, bytes)
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

    fun csv(text: String) = Tables.csv(text)

    fun xlsx(bytes: ByteArray) = Tables.xlsx(bytes)
}
