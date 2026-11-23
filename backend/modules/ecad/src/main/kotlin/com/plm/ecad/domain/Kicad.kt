package com.plm.ecad.domain

/** A KiCad S-expression: a list (its head is the first atom, e.g. `symbol`) or an atom. */
internal sealed class SExpr {
    data class Atom(
        val text: String,
    ) : SExpr()

    data class SList(
        val items: List<SExpr>,
    ) : SExpr() {
        val head: String? get() = (items.firstOrNull() as? Atom)?.text

        /** Child lists named [name]. */
        fun all(name: String): List<SList> = items.filterIsInstance<SList>().filter { it.head == name }

        fun one(name: String): SList? = items.filterIsInstance<SList>().firstOrNull { it.head == name }

        /** The [i]th atom after the head. */
        fun atom(i: Int): String? = (items.getOrNull(i + 1) as? Atom)?.text

        fun double(i: Int): Double = atom(i)?.toDoubleOrNull() ?: 0.0

        /** `(property "Reference" "R1")` → R1. */
        fun property(name: String): String? = all("property").firstOrNull { it.atom(0) == name }?.atom(1)

        /** Every list named [name] at any depth. */
        fun deep(name: String): List<SList> =
            items.filterIsInstance<SList>().flatMap {
                (
                    if (it.head ==
                        name
                    ) {
                        listOf(it)
                    } else {
                        emptyList()
                    }
                ) + it.deep(name)
            }
    }

    companion object {
        /** Parses KiCad's s-expression files (.kicad_sch, .kicad_pcb, sym-lib-table…). */
        fun parse(text: String): SList {
            var i = 0
            val stack = ArrayDeque<MutableList<SExpr>>()
            var root: SList? = null
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '(' -> {
                        stack.addLast(mutableListOf())
                        i++
                    }
                    c == ')' -> {
                        val list = SList(stack.removeLast())
                        if (stack.isEmpty()) {
                            root = root ?: list
                        } else {
                            stack.last() += list
                        }
                        i++
                    }
                    c.isWhitespace() -> i++
                    c == '"' -> {
                        val sb = StringBuilder()
                        i++
                        while (i < text.length && text[i] != '"') {
                            if (text[i] == '\\' && i + 1 < text.length) {
                                i++
                                sb.append(if (text[i] == 'n') '\n' else text[i])
                            } else {
                                sb.append(text[i])
                            }
                            i++
                        }
                        i++
                        stack.lastOrNull()?.add(Atom(sb.toString()))
                    }
                    else -> {
                        val start = i
                        while (i < text.length && !text[i].isWhitespace() && text[i] != '(' && text[i] != ')') i++
                        stack.lastOrNull()?.add(Atom(text.substring(start, i)))
                    }
                }
            }
            return root ?: throw IllegalArgumentException("Not an s-expression file")
        }
    }
}

/** A placed component of a schematic: one per reference designator. */
internal data class Component(
    val reference: String,
    val value: String,
    val footprint: String,
    val libId: String,
    /** A manufacturer part number, if the schematic has one (MPN, Part Number, PN…). */
    val partNumber: String?,
    val sheet: String,
)

internal data class Sheet(
    val file: String,
    val name: String,
    /** Sub-sheets it places, by their file and name. */
    val children: List<Pair<String, String>>,
)

/** One BOM line: the components that are the same part (value, footprint, part number). */
internal data class BomLine(
    val value: String,
    val footprint: String,
    val partNumber: String?,
    val references: List<String>,
) {
    /** The name a new part gets: e.g. `100nF (C_0805)`. */
    val partName: String get() = if (footprint.isBlank()) value else "$value (${footprint.substringAfter(':')})"
}

internal data class Footprint(
    val reference: String,
    val x: Double,
    val y: Double,
    val rotation: Double,
    val back: Boolean,
    val pads: List<Pad>,
)

internal data class Pad(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val round: Boolean,
)

internal data class Segment(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val width: Double,
    val layer: String,
)

internal data class Board(
    val outline: List<Segment>,
    val footprints: List<Footprint>,
    val tracks: List<Segment>,
    val copperLayers: Int,
) {
    val minX get() = (outline.flatMap { listOf(it.x1, it.x2) } + footprints.map { it.x }).minOrNull() ?: 0.0
    val maxX get() = (outline.flatMap { listOf(it.x1, it.x2) } + footprints.map { it.x }).maxOrNull() ?: 0.0
    val minY get() = (outline.flatMap { listOf(it.y1, it.y2) } + footprints.map { it.y }).minOrNull() ?: 0.0
    val maxY get() = (outline.flatMap { listOf(it.y1, it.y2) } + footprints.map { it.y }).maxOrNull() ?: 0.0
}

/** Reads a KiCad project's files (KiCad 6 and later). */
internal object Kicad {
    private val PART_NUMBER = listOf("MPN", "Manufacturer Part Number", "Part Number", "PartNumber", "PN", "Mfr. No")

    /** A schematic file's sheet: its name and the sub-sheets it places. */
    fun sheet(
        file: String,
        name: String,
        root: SExpr.SList,
    ) = Sheet(
        file,
        name,
        root.all("sheet").mapNotNull { s ->
            val f = s.property("Sheetfile") ?: s.property("Sheet file") ?: return@mapNotNull null
            f to (s.property("Sheetname") ?: s.property("Sheet name") ?: f.substringBeforeLast('.'))
        },
    )

    /**
     * The components a schematic file places: power symbols (#PWR…) and those not in the BOM left
     * out. A sheet used more than once lists each use's references in its `instances`.
     */
    fun components(
        file: String,
        root: SExpr.SList,
    ): List<Component> =
        root.all("symbol").filter { it.one("lib_id") != null }.flatMap { s ->
            if (s.one("in_bom")?.atom(0) == "no" || s.one("dnp")?.atom(0) == "yes") return@flatMap emptyList()
            val value = s.property("Value").orEmpty()
            val footprint = s.property("Footprint").orEmpty()
            val pn =
                PART_NUMBER.firstNotNullOfOrNull { key ->
                    s.property(key)?.takeIf { it.isNotBlank() && it != "~" }
                }
            val libId = s.one("lib_id")?.atom(0).orEmpty()
            val refs =
                s
                    .deep(
                        "path",
                    ).mapNotNull { it.one("reference")?.atom(0) }
                    .ifEmpty { listOfNotNull(s.property("Reference")) }
            refs
                .filterNot {
                    it.startsWith(
                        "#",
                    ) ||
                        it.endsWith("?")
                }.map { Component(it, value, footprint, libId, pn, file) }
        }

    /** The BOM: components grouped by what they are, references in natural order. */
    fun bom(components: List<Component>): List<BomLine> =
        components
            .distinctBy { it.reference }
            .groupBy { Triple(it.value, it.footprint, it.partNumber) }
            .map { (k, cs) -> BomLine(k.first, k.second, k.third, cs.map { it.reference }.sortedWith(naturalOrder)) }
            .sortedWith(
                compareBy({ it.references.first().takeWhile(Char::isLetter) }, { naturalKey(it.references.first()) }),
            )

    private fun naturalKey(ref: String) = ref.dropWhile(Char::isLetter).toIntOrNull() ?: Int.MAX_VALUE

    private val naturalOrder =
        Comparator<String> { a, b ->
            val pa = a.takeWhile(Char::isLetter)
            val pb = b.takeWhile(Char::isLetter)
            if (pa != pb) pa.compareTo(pb) else naturalKey(a).compareTo(naturalKey(b))
        }

    fun board(root: SExpr.SList): Board {
        fun seg(
            s: SExpr.SList,
            layer: String,
        ): Segment? {
            val a = s.one("start") ?: return null
            val b = s.one("end") ?: return null
            return Segment(
                a.double(0),
                a.double(1),
                b.double(0),
                b.double(1),
                s.one("width")?.double(0) ?: s.one("stroke")?.one("width")?.double(0) ?: 0.15,
                layer,
            )
        }
        val outline =
            (root.all("gr_line") + root.all("gr_rect") + root.all("gr_arc")).flatMap { g ->
                val layer = g.one("layer")?.atom(0) ?: return@flatMap emptyList()
                if (layer != "Edge.Cuts") return@flatMap emptyList()
                if (g.head == "gr_rect") {
                    val a = g.one("start")!!
                    val b = g.one("end")!!
                    val (x1, y1, x2, y2) = listOf(a.double(0), a.double(1), b.double(0), b.double(1))
                    listOf(
                        Segment(x1, y1, x2, y1, 0.1, layer),
                        Segment(x2, y1, x2, y2, 0.1, layer),
                        Segment(x2, y2, x1, y2, 0.1, layer),
                        Segment(x1, y2, x1, y1, 0.1, layer),
                    )
                } else if (g.head == "gr_arc" && g.one("mid") != null) {
                    // Three points: drawn as two chords, enough for a preview.
                    val a = g.one("start")!!
                    val m = g.one("mid")!!
                    val b = g.one("end")!!
                    listOf(
                        Segment(a.double(0), a.double(1), m.double(0), m.double(1), 0.1, layer),
                        Segment(m.double(0), m.double(1), b.double(0), b.double(1), 0.1, layer),
                    )
                } else {
                    listOfNotNull(seg(g, layer))
                }
            }
        val footprints =
            root.all("footprint").map { f ->
                val at = f.one("at")
                val reference =
                    f.property("Reference") ?: f.all("fp_text").firstOrNull { it.atom(0) == "reference" }?.atom(1) ?: ""
                Footprint(
                    reference,
                    at?.double(0) ?: 0.0,
                    at?.double(1) ?: 0.0,
                    at?.double(2) ?: 0.0,
                    f.one("layer")?.atom(0) == "B.Cu",
                    f.all("pad").map { p ->
                        val pat = p.one("at")
                        val size = p.one("size")
                        Pad(
                            pat?.double(0) ?: 0.0,
                            pat?.double(1) ?: 0.0,
                            size?.double(0) ?: 1.0,
                            size?.double(1) ?: 1.0,
                            p.atom(2) in setOf("circle", "oval"),
                        )
                    },
                )
            }
        val tracks = root.all("segment").mapNotNull { s -> seg(s, s.one("layer")?.atom(0) ?: "F.Cu") }
        val copper =
            root
                .one("layers")
                ?.items
                ?.filterIsInstance<SExpr.SList>()
                ?.count { (it.atom(0) ?: "").endsWith(".Cu") }
                ?: 2
        return Board(outline, footprints, tracks, copper)
    }
}
