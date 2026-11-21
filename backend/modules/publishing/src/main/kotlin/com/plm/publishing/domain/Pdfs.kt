package com.plm.publishing.domain

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** What a data sheet shows (roadmap 6.7). */
internal data class DataSheet(
    val title: String,
    val subtitle: String,
    val facts: List<Pair<String, String>>,
    val sections: List<Section>,
    val footer: String,
) {
    data class Section(
        val title: String,
        val header: List<String>,
        val rows: List<List<String>>,
        /** Relative column widths. */
        val widths: List<Float>,
    )
}

/** PDF making and stamping with PDFBox, in the standard fonts. */
internal object Pdfs {
    private val regular get() = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold get() = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    /** Keeps what the standard fonts can print (WinAnsi); the rest becomes '?'. */
    fun safe(
        s: String,
        font: PDType1Font = regular,
    ): String =
        buildString {
            for (c in s.replace('\n', ' ').replace('\t', ' ')) {
                append(
                    try {
                        font.encode(c.toString())
                        c
                    } catch (e: IllegalArgumentException) {
                        when (c) {
                            '→' -> '>'
                            '↔' -> '-'
                            '·' -> '-'
                            else -> '?'
                        }
                    },
                )
            }
        }

    private fun width(
        font: PDType1Font,
        size: Float,
        s: String,
    ) = font.getStringWidth(s) / 1000f * size

    private fun fit(
        font: PDType1Font,
        size: Float,
        s: String,
        max: Float,
    ): String {
        var t = safe(s, font)
        if (width(font, size, t) <= max) return t
        while (t.isNotEmpty() && width(font, size, "$t…".replace("…", "...")) > max) t = t.dropLast(1)
        return "$t..."
    }

    fun datasheet(sheet: DataSheet): Pair<ByteArray, Int> {
        PDDocument().use { doc ->
            val margin = 48f
            val pageSize = PDRectangle.A4
            val usable = pageSize.width - 2 * margin
            var page = PDPage(pageSize)
            doc.addPage(page)
            var cs = PDPageContentStream(doc, page)
            var y = pageSize.height - margin

            fun text(
                s: String,
                x: Float,
                yy: Float,
                font: PDType1Font,
                size: Float,
                gray: Float = 0.1f,
            ) {
                cs.setNonStrokingColor(gray, gray, gray)
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset(x, yy)
                cs.showText(s)
                cs.endText()
            }

            fun newPage() {
                cs.close()
                page = PDPage(pageSize)
                doc.addPage(page)
                cs = PDPageContentStream(doc, page)
                y = pageSize.height - margin
            }

            fun need(h: Float) {
                if (y - h < margin + 24) newPage()
            }
            text(fit(bold, 18f, sheet.title, usable), margin, y - 18, bold, 18f)
            y -= 26
            text(fit(regular, 10f, sheet.subtitle, usable), margin, y - 10, regular, 10f, 0.35f)
            y -= 22
            cs.setStrokingColor(0.8f, 0.82f, 0.86f)
            cs.moveTo(margin, y)
            cs.lineTo(pageSize.width - margin, y)
            cs.stroke()
            y -= 14
            for ((k, v) in sheet.facts) {
                need(14f)
                text(fit(regular, 9.5f, k, 130f), margin, y - 9, regular, 9.5f, 0.4f)
                text(fit(regular, 9.5f, v, usable - 140), margin + 140, y - 9, regular, 9.5f)
                y -= 14
            }
            for (section in sheet.sections) {
                need(40f)
                y -= 12
                text(safe(section.title.uppercase(), bold), margin, y - 10, bold, 9.5f, 0.3f)
                y -= 18
                val total = section.widths.sum()
                val cols = section.widths.map { it / total * usable }

                fun row(
                    cells: List<String>,
                    font: PDType1Font,
                    shade: Boolean,
                ) {
                    need(15f)
                    if (shade) {
                        cs.setNonStrokingColor(0.95f, 0.96f, 0.97f)
                        cs.addRect(margin, y - 13, usable, 15f)
                        cs.fill()
                    }
                    var x = margin + 4
                    cells.forEachIndexed { i, c ->
                        text(fit(font, 8.5f, c, cols[i] - 8), x, y - 9, font, 8.5f, if (font == bold) 0.3f else 0.1f)
                        x += cols[i]
                    }
                    y -= 15
                }
                row(section.header, bold, true)
                if (section.rows.isEmpty()) row(listOf("-") + List(section.header.size - 1) { "" }, regular, false)
                section.rows.forEachIndexed { i, r -> row(r, regular, i % 2 == 1) }
            }
            cs.close()
            val pages = doc.numberOfPages
            doc.pages.forEachIndexed { i, p ->
                PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true).use { c ->
                    c.setNonStrokingColor(0.5f, 0.5f, 0.5f)
                    c.beginText()
                    c.setFont(regular, 7.5f)
                    c.newLineAtOffset(margin, 24f)
                    c.showText(fit(regular, 7.5f, "${sheet.footer}  -  page ${i + 1} of $pages", usable))
                    c.endText()
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray() to pages
        }
    }

    /**
     * Stamps every page: a band of [footer] text at the bottom, and [diagonal] (e.g. RELEASED)
     * across the page, light, when given.
     */
    fun stamp(
        pdf: ByteArray,
        footer: String,
        diagonal: String?,
        top: Boolean = false,
    ): Pair<ByteArray, Int> {
        Loader.loadPDF(pdf).use { doc ->
            for (page in doc.pages) {
                val box = page.mediaBox
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { c ->
                    val gs = PDExtendedGraphicsState()
                    gs.nonStrokingAlphaConstant = 0.85f
                    c.setGraphicsStateParameters(gs)
                    c.setNonStrokingColor(1f, 1f, 1f)
                    val bandY = if (top) box.upperRightY - 16f else box.lowerLeftY
                    c.addRect(box.lowerLeftX, bandY, box.width, 16f)
                    c.fill()
                    c.setNonStrokingColor(0.15f, 0.15f, 0.15f)
                    c.beginText()
                    c.setFont(bold, 7.5f)
                    c.newLineAtOffset(box.lowerLeftX + 10, bandY + 5)
                    c.showText(fit(bold, 7.5f, footer, box.width - 20))
                    c.endText()
                    if (diagonal != null) {
                        val faint = PDExtendedGraphicsState()
                        faint.nonStrokingAlphaConstant = 0.12f
                        c.setGraphicsStateParameters(faint)
                        val size = minOf(box.width, box.height) / 9
                        val angle = atan2(box.height.toDouble(), box.width.toDouble())
                        val text = safe(diagonal, bold)
                        val w = width(bold, size, text)
                        val cx = box.lowerLeftX + box.width / 2
                        val cy = box.lowerLeftY + box.height / 2
                        c.setNonStrokingColor(0.8f, 0.1f, 0.1f)
                        c.beginText()
                        c.setFont(bold, size)
                        c.setTextMatrix(
                            Matrix(
                                cos(angle).toFloat(),
                                sin(angle).toFloat(),
                                -sin(angle).toFloat(),
                                cos(angle).toFloat(),
                                (cx - cos(angle) * w / 2).toFloat(),
                                (cy - sin(angle) * w / 2).toFloat(),
                            ),
                        )
                        c.showText(text)
                        c.endText()
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray() to doc.numberOfPages
        }
    }
}
