package com.plm.cad.domain

import com.plm.cad.api.Transforms
import com.plm.platform.error.ValidationException
import kotlin.math.sqrt

/** A STEP (ISO 10303-21) value. */
internal sealed interface P21 {
    data class Ref(
        val id: Int,
    ) : P21

    data class Str(
        val value: String,
    ) : P21

    data class Num(
        val value: Double,
    ) : P21

    data class Enum(
        val value: String,
    ) : P21

    data class Lst(
        val items: List<P21>,
    ) : P21

    /** A typed value, e.g. LENGTH_MEASURE(1.0). */
    data class Typed(
        val type: String,
        val args: List<P21>,
    ) : P21

    data object Unset : P21
}

/** One instance: simple (one part) or complex (several, as in `(A(...)B(...))`). */
internal data class StepEntity(
    val id: Int,
    val parts: Map<String, List<P21>>,
) {
    val type: String get() = parts.keys.first()

    fun args(type: String = this.type) = parts[type]
}

/** Reads a Part 21 file's DATA section (roadmap 6.5). Enough for product structure and placements. */
internal class StepFile private constructor(
    val schema: String,
    val entities: Map<Int, StepEntity>,
) {
    companion object {
        fun parse(text: String): StepFile {
            val head = text.indexOf("FILE_SCHEMA")
            val schema =
                if (head >= 0) {
                    Regex("'([^']*)'").find(text, head)?.groupValues?.get(1) ?: ""
                } else {
                    ""
                }
            val data = text.indexOf("DATA;")
            if (!text.trimStart().startsWith("ISO-10303-21") || data < 0) {
                throw ValidationException("INVALID_STEP", "This isn't a STEP file (ISO 10303-21)")
            }
            val entities = HashMap<Int, StepEntity>()
            var i = data + 5
            val n = text.length
            val sb = StringBuilder()
            var inString = false
            while (i < n) {
                val c = text[i]
                if (inString) {
                    sb.append(c)
                    if (c == '\'') {
                        if (i + 1 < n && text[i + 1] == '\'') {
                            sb.append('\'')
                            i++
                        } else {
                            inString = false
                        }
                    }
                } else if (c == '\'') {
                    inString = true
                    sb.append(c)
                } else if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) n else end + 1
                } else if (c == ';') {
                    val statement = sb.toString().trim()
                    sb.setLength(0)
                    if (statement == "ENDSEC") break
                    if (statement.startsWith("#")) {
                        val eq = statement.indexOf('=')
                        val id = statement.substring(1, eq).trim().toInt()
                        entities[id] = StepEntity(id, Parser(statement.substring(eq + 1).trim()).instance())
                    }
                } else if (!c.isWhitespace() || sb.isNotEmpty()) {
                    sb.append(c)
                }
                i++
            }
            return StepFile(schema, entities)
        }
    }

    fun ofType(type: String) = entities.values.filter { type in it.parts }

    fun ref(v: P21?): StepEntity? = (v as? P21.Ref)?.let { entities[it.id] }

    private class Parser(
        val s: String,
    ) {
        var p = 0

        fun skip() {
            while (p < s.length && s[p].isWhitespace()) p++
        }

        fun name(): String {
            skip()
            val start = p
            while (p < s.length && (s[p].isLetterOrDigit() || s[p] == '_')) p++
            return s.substring(start, p).uppercase()
        }

        fun instance(): Map<String, List<P21>> {
            skip()
            return if (s[p] == '(') {
                p++
                val parts = LinkedHashMap<String, List<P21>>()
                while (true) {
                    skip()
                    if (s[p] == ')') break
                    val n = name()
                    parts[n] = list()
                }
                parts
            } else {
                val n = name()
                linkedMapOf(n to list())
            }
        }

        fun list(): List<P21> {
            skip()
            check(s[p] == '(') { "Expected ( at $p in $s" }
            p++
            val items = mutableListOf<P21>()
            while (true) {
                skip()
                if (s[p] == ')') {
                    p++
                    return items
                }
                items += value()
                skip()
                if (s[p] == ',') p++
            }
        }

        fun value(): P21 {
            skip()
            val c = s[p]
            return when {
                c == '#' -> {
                    p++
                    val start = p
                    while (p < s.length && s[p].isDigit()) p++
                    P21.Ref(s.substring(start, p).toInt())
                }
                c == '\'' -> {
                    p++
                    val out = StringBuilder()
                    while (true) {
                        if (s[p] == '\'') {
                            if (p + 1 < s.length && s[p + 1] == '\'') {
                                out.append('\'')
                                p += 2
                                continue
                            }
                            p++
                            break
                        }
                        out.append(s[p++])
                    }
                    P21.Str(out.toString())
                }
                c == '(' -> P21.Lst(list())
                c == '.' -> {
                    val end = s.indexOf('.', p + 1)
                    P21.Enum(s.substring(p + 1, end)).also { p = end + 1 }
                }
                c == '$' || c == '*' -> {
                    p++
                    P21.Unset
                }
                c.isLetter() -> {
                    val n = name()
                    P21.Typed(n, list())
                }
                else -> {
                    val start = p
                    while (p < s.length && (s[p].isDigit() || s[p] in "+-.eE")) p++
                    P21.Num(s.substring(start, p).toDoubleOrNull() ?: 0.0)
                }
            }
        }
    }
}

/** A product (a model) in a STEP file. */
internal data class StepProduct(
    /** The PRODUCT_DEFINITION's id. */
    val key: Int,
    val partNumber: String,
    val name: String,
)

internal data class StepUsage(
    val parent: Int,
    val child: Int,
    val occurrence: String,
    /** Column-major, millimetres. */
    val transform: List<Double>,
)

/** The product structure of a STEP file (roadmap 6.5). */
internal data class StepStructure(
    val schema: String,
    /** Millimetres per file unit. */
    val unitToMm: Double,
    val products: Map<Int, StepProduct>,
    val usages: List<StepUsage>,
) {
    val roots: List<Int> get() = products.keys.filter { k -> usages.none { it.child == k } }

    fun children(parent: Int) = usages.filter { it.parent == parent }

    /** Each product after everything it uses (so assemblies come after their components). */
    fun bottomUp(): List<Int> {
        val out = LinkedHashSet<Int>()

        fun visit(
            k: Int,
            path: Set<Int>,
        ) {
            if (k in out || k in path) return
            children(k).forEach { visit(it.child, path + k) }
            out += k
        }
        roots.forEach { visit(it, emptySet()) }
        products.keys.forEach { visit(it, emptySet()) }
        return out.toList()
    }

    companion object {
        fun read(text: String): StepStructure {
            val file = StepFile.parse(text)
            val unit = unitToMm(file)
            val products =
                file.ofType("PRODUCT_DEFINITION").associate { pd ->
                    val formation = file.ref(pd.args("PRODUCT_DEFINITION")!![2])
                    val product =
                        formation
                            ?.parts
                            ?.values
                            ?.firstOrNull()
                            ?.getOrNull(2)
                            ?.let { file.ref(it) }
                    val args = product?.args("PRODUCT")
                    val number = (args?.getOrNull(0) as? P21.Str)?.value.orEmpty()
                    val name = (args?.getOrNull(1) as? P21.Str)?.value?.ifBlank { null } ?: number
                    pd.id to StepProduct(pd.id, number, name.ifBlank { "Product #${pd.id}" })
                }
            // Placement of each usage: shape definition → context dependent shape representation.
            val placementByUsage = HashMap<Int, List<Double>>()
            for (cdsr in file.ofType("CONTEXT_DEPENDENT_SHAPE_REPRESENTATION")) {
                val args = cdsr.args()!!
                val relationship = file.ref(args[0]) ?: continue
                val pds = file.ref(args[1]) ?: continue
                val usage = (pds.args()?.getOrNull(2) as? P21.Ref)?.id ?: continue
                val rrwt = relationship.args("REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION") ?: continue
                val transformation = file.ref(rrwt[0]) ?: continue
                placementByUsage[usage] =
                    when (transformation.type) {
                        "ITEM_DEFINED_TRANSFORMATION" -> {
                            val t = transformation.args()!!
                            val from = placement(file, file.ref(t[2]), unit)
                            val to = placement(file, file.ref(t[3]), unit)
                            Transforms.multiply(to, invertRigid(from))
                        }
                        else -> Transforms.IDENTITY
                    }
            }
            val usages =
                file.ofType("NEXT_ASSEMBLY_USAGE_OCCURRENCE").mapNotNull { u ->
                    val a = u.args()!!
                    val parent = (a[3] as? P21.Ref)?.id ?: return@mapNotNull null
                    val child = (a[4] as? P21.Ref)?.id ?: return@mapNotNull null
                    if (parent !in products || child !in products) return@mapNotNull null
                    val id = (a[0] as? P21.Str)?.value.orEmpty()
                    val name = (a[1] as? P21.Str)?.value.orEmpty()
                    StepUsage(
                        parent,
                        child,
                        name.ifBlank { id }.ifBlank { "#${u.id}" },
                        placementByUsage[u.id] ?: Transforms.IDENTITY,
                    )
                }
            // Occurrences named after their model, numbered per assembly (NAUO names are rarely meaningful).
            val counters = HashMap<Pair<Int, Int>, Int>()
            val named =
                usages.sortedBy { it.occurrence }.map { u ->
                    val n = counters.merge(u.parent to u.child, 1, Int::plus)!!
                    u.copy(occurrence = "${products.getValue(u.child).name}:$n")
                }
            return StepStructure(file.schema, unit, products, named)
        }

        private fun unitToMm(file: StepFile): Double {
            for (e in file.entities.values) {
                if ("LENGTH_UNIT" !in e.parts) continue
                e.parts["CONVERSION_BASED_UNIT"]?.let { a ->
                    val name = (a.getOrNull(0) as? P21.Str)?.value?.uppercase().orEmpty()
                    return when {
                        name.startsWith("INCH") -> 25.4
                        name.startsWith("FOOT") -> 304.8
                        else -> 1.0
                    }
                }
                e.parts["SI_UNIT"]?.let { a ->
                    return when ((a.getOrNull(0) as? P21.Enum)?.value) {
                        "MILLI" -> 1.0
                        "CENTI" -> 10.0
                        "MICRO" -> 0.001
                        null -> 1000.0
                        else -> 1000.0
                    }
                }
            }
            return 1.0
        }

        private fun vector(
            file: StepFile,
            e: StepEntity?,
        ): DoubleArray? {
            val list = (e?.args()?.getOrNull(1) as? P21.Lst)?.items ?: return null
            return DoubleArray(3) { (list.getOrNull(it) as? P21.Num)?.value ?: 0.0 }
        }

        /** An AXIS2_PLACEMENT_3D as a column-major matrix (millimetres). */
        private fun placement(
            file: StepFile,
            e: StepEntity?,
            unit: Double,
        ): List<Double> {
            val a = e?.args("AXIS2_PLACEMENT_3D") ?: return Transforms.IDENTITY
            val origin = vector(file, file.ref(a[1])) ?: doubleArrayOf(0.0, 0.0, 0.0)
            val z = normalize(vector(file, file.ref(a.getOrNull(2))) ?: doubleArrayOf(0.0, 0.0, 1.0))
            var x =
                vector(file, file.ref(a.getOrNull(3)))
                    ?: if (kotlin.math.abs(z[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
            val d = x[0] * z[0] + x[1] * z[1] + x[2] * z[2]
            x = normalize(doubleArrayOf(x[0] - d * z[0], x[1] - d * z[1], x[2] - d * z[2]))
            val y = doubleArrayOf(z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0])
            return listOf(
                x[0],
                x[1],
                x[2],
                0.0,
                y[0],
                y[1],
                y[2],
                0.0,
                z[0],
                z[1],
                z[2],
                0.0,
                origin[0] * unit,
                origin[1] * unit,
                origin[2] * unit,
                1.0,
            )
        }

        private fun normalize(v: DoubleArray): DoubleArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 1e-12 } ?: return v
            return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }

        /** The inverse of a rotation and translation. */
        private fun invertRigid(m: List<Double>): List<Double> {
            val r = listOf(m[0], m[4], m[8], 0.0, m[1], m[5], m[9], 0.0, m[2], m[6], m[10], 0.0)
            val tx = -(r[0] * m[12] + r[4] * m[13] + r[8] * m[14])
            val ty = -(r[1] * m[12] + r[5] * m[13] + r[9] * m[14])
            val tz = -(r[2] * m[12] + r[6] * m[13] + r[10] * m[14])
            return r + listOf(tx, ty, tz, 1.0)
        }
    }
}
