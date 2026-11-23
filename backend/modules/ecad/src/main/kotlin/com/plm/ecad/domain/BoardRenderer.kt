package com.plm.ecad.domain

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A picture of a board (roadmap 8.7): outline, tracks on both sides, pads, references. */
internal object BoardRenderer {
    private val BOARD = Color(0x0E, 0x4A, 0x2A)
    private val OUTLINE = Color(0xE8, 0xD4, 0x4D)
    private val FRONT = Color(0xD0, 0x4A, 0x3A)
    private val BACK = Color(0x4A, 0x7C, 0xD8)
    private val PAD = Color(0xD9, 0xB2, 0x4C)
    private val TEXT = Color(0xF2, 0xF2, 0xF2, 0xC0)

    fun png(
        board: Board,
        width: Int = 1600,
    ): ByteArray {
        val margin = 4.0
        val minX = board.minX - margin
        val minY = board.minY - margin
        val w = max(1.0, board.maxX - board.minX + 2 * margin)
        val h = max(1.0, board.maxY - board.minY + 2 * margin)
        val scale = width / w
        val height = max(1, (h * scale).toInt())
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x16, 0x1B, 0x22)
        g.fillRect(0, 0, width, height)

        fun x(v: Double) = (v - minX) * scale

        fun y(v: Double) = (v - minY) * scale
        // The board's area, then what's on it: back side first.
        g.color = BOARD
        g.fill(
            Rectangle2D.Double(
                x(board.minX),
                y(board.minY),
                (board.maxX - board.minX) * scale,
                (board.maxY - board.minY) * scale,
            ),
        )
        for (layer in listOf("B.Cu", "F.Cu")) {
            g.color = if (layer == "F.Cu") FRONT else BACK
            for (t in board.tracks.filter { it.layer == layer }) {
                g.stroke = BasicStroke((t.width * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Double(x(t.x1), y(t.y1), x(t.x2), y(t.y2)))
            }
        }
        g.color = PAD
        for (f in board.footprints) {
            val r = Math.toRadians(f.rotation)
            for (p in f.pads) {
                // KiCad: pad positions relative to the footprint, which is rotated counter-clockwise (y down).
                val px = f.x + p.x * cos(r) + p.y * sin(r)
                val py = f.y - p.x * sin(r) + p.y * cos(r)
                val pw = p.w * scale
                val ph = p.h * scale
                if (p.round) {
                    g.fill(Ellipse2D.Double(x(px) - pw / 2, y(py) - ph / 2, pw, ph))
                } else {
                    g.fill(Rectangle2D.Double(x(px) - pw / 2, y(py) - ph / 2, pw, ph))
                }
            }
        }
        g.color = OUTLINE
        g.stroke = BasicStroke(max(2f, (0.3 * scale).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        board.outline.forEach { g.draw(Line2D.Double(x(it.x1), y(it.y1), x(it.x2), y(it.y2))) }
        g.color = TEXT
        g.font = Font(Font.SANS_SERIF, Font.BOLD, max(9, (1.1 * scale).toInt()))
        for (f in board.footprints.filter { it.reference.isNotBlank() && !it.back }) {
            val fm = g.fontMetrics
            g.drawString(
                f.reference,
                (x(f.x) - fm.stringWidth(f.reference) / 2).toFloat(),
                (y(f.y) + fm.ascent / 2).toFloat(),
            )
        }
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }
}
