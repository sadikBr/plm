package com.plm.ecad.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** On KiCad's own demo project (pic_programmer, from the KiCad source tree). */
class KicadTest {
    private fun read(name: String) = SExpr.parse(javaClass.getResource("/kicad/$name")!!.readText())

    @Test
    fun `the root schematic places its sub-sheet`() {
        val sheet = Kicad.sheet("pic_programmer.kicad_sch", "Root", read("pic_programmer.kicad_sch"))
        assertThat(sheet.children.map { it.first }).contains("pic_sockets.kicad_sch")
    }

    @Test
    fun `components become BOM lines with their references, power symbols left out`() {
        val components =
            Kicad.components("pic_programmer.kicad_sch", read("pic_programmer.kicad_sch")) +
                Kicad.components("pic_sockets.kicad_sch", read("pic_sockets.kicad_sch"))
        assertThat(components).isNotEmpty
        assertThat(components.map { it.reference }).noneMatch { it.startsWith("#") }
        val bom = Kicad.bom(components)
        assertThat(bom.sumOf { it.references.size }).isEqualTo(components.map { it.reference }.distinct().size)
        val resistors = bom.filter { it.references.first().startsWith("R") }
        assertThat(resistors).isNotEmpty
        println(
            "BOM: ${bom.size} lines, ${components.size} components; e.g. ${bom.take(
                4,
            ).map { "${it.partName} ×${it.references.size} ${it.references}" }}",
        )
    }

    @Test
    fun `the board has an outline, footprints for the components, and tracks`() {
        val board = Kicad.board(read("pic_programmer.kicad_pcb"))
        assertThat(board.outline).isNotEmpty
        assertThat(board.footprints.size).isGreaterThan(20)
        assertThat(board.tracks).isNotEmpty
        assertThat(board.maxX - board.minX).isGreaterThan(50.0)
        println(
            "Board: ${board.footprints.size} footprints, ${board.tracks.size} tracks, ${board.copperLayers} copper layers, ${(board.maxX - board.minX).toInt()}×${(board.maxY - board.minY).toInt()} mm",
        )
    }
}
