package com.plm.bom.domain

import com.plm.platform.error.ValidationException

/**
 * Reference designators (roadmap 4.5): `R1-R4, R7 C2` means R1, R2, R3, R4, R7, C2. Ranges keep
 * their prefix (R1-R4 or R1-4). At most 10,000 per line.
 */
internal object Designators {
    private val ONE = Regex("""[A-Za-z][A-Za-z0-9_.]*?\d*""")
    private val RANGE = Regex("""([A-Za-z][A-Za-z_]*)(\d+)-(?:([A-Za-z][A-Za-z_]*))?(\d+)""")

    fun parse(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val normalized = text.replace(Regex("""\s*[-–]\s*"""), "-")
        for (token in normalized.split(Regex("""[,;\s]+""")).filter { it.isNotEmpty() }) {
            val range = RANGE.matchEntire(token)
            if (range != null) {
                val (prefix, from, prefix2, to) = range.destructured
                if (prefix2.isNotEmpty() && prefix2 != prefix) bad("$token: a range keeps one prefix")
                val a = from.toInt()
                val b = to.toInt()
                if (b < a || b - a > 10_000) bad("$token isn't a valid range")
                for (n in a..b) out += "$prefix$n"
            } else {
                if (!ONE.matches(token) || token.length > 40) bad("$token isn't a reference designator")
                out += token
            }
            if (out.size > 10_000) bad("At most 10,000 designators per line")
        }
        return out.toList()
    }

    /** R1, R2, R3, R7 → R1-R3, R7. */
    fun format(list: List<String>): String {
        val parts = mutableListOf<String>()
        var i = 0
        val split =
            list.map { d ->
                Regex("""^(.*?)(\d+)$""").matchEntire(d)?.destructured?.let { (p, n) -> Triple(d, p, n.toInt()) }
                    ?: Triple(d, d, -1)
            }
        while (i < split.size) {
            val (d, p, n) = split[i]
            var j = i
            while (n >= 0 &&
                j + 1 < split.size &&
                split[j + 1].second == p &&
                split[j + 1].third == split[j].third + 1
            ) {
                j++
            }
            parts += if (j - i >= 2) "$d-${split[j].first}" else (i..j).joinToString(", ") { split[it].first }
            i = j + 1
        }
        return parts.joinToString(", ")
    }

    private fun bad(message: String): Nothing = throw ValidationException("INVALID_DESIGNATORS", message)
}
