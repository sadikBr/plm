package com.plm.variants.domain

import com.plm.platform.error.ValidationException
import com.plm.variants.api.OptionSet

/**
 * A line's condition (roadmap 7.7): `MOTOR=HT`, `COLOR in (SAND, BLUE)`, combined with `and`, `or`,
 * `not` and parentheses; `not` binds tightest, then `and`, then `or`. Codes are case-insensitive.
 */
internal sealed interface Condition {
    fun holds(selection: Map<String, String>): Boolean

    /** The options it refers to. */
    fun options(): Set<String>

    data class Is(
        val option: String,
        val choices: Set<String>,
    ) : Condition {
        override fun holds(selection: Map<String, String>) = selection[option] in choices

        override fun options() = setOf(option)
    }

    data class Not(
        val inner: Condition,
    ) : Condition {
        override fun holds(selection: Map<String, String>) = !inner.holds(selection)

        override fun options() = inner.options()
    }

    data class All(
        val parts: List<Condition>,
    ) : Condition {
        override fun holds(selection: Map<String, String>) = parts.all { it.holds(selection) }

        override fun options() = parts.flatMap { it.options() }.toSet()
    }

    data class Any(
        val parts: List<Condition>,
    ) : Condition {
        override fun holds(selection: Map<String, String>) = parts.any { it.holds(selection) }

        override fun options() = parts.flatMap { it.options() }.toSet()
    }

    companion object {
        private val TOKEN = Regex("""\s*(\(|\)|,|=|[A-Za-z][A-Za-z0-9_]*)""")

        private fun bad(message: String): Nothing = throw ValidationException("INVALID_CONDITION", message)

        /** Parses [text]; with [set], also checks every option and choice exists in it. */
        fun parse(
            text: String,
            set: OptionSet? = null,
        ): Condition {
            val tokens = ArrayList<String>()
            var i = 0
            val s = text.trim()
            if (s.isEmpty()) bad("Write a condition, e.g. MOTOR=HT")
            if (s.length > 2000) bad("A condition is at most 2,000 characters")
            while (i < s.length) {
                val m = TOKEN.matchAt(s, i) ?: bad("Can't read '${s.substring(i).trim().take(20)}'")
                tokens += m.groupValues[1]
                i = m.range.last + 1
                while (i < s.length && s[i].isWhitespace()) i++
            }
            var p = 0

            fun peek() = tokens.getOrNull(p)

            fun word(w: String) = peek()?.equals(w, ignoreCase = true) == true

            fun take(): String = tokens.getOrNull(p++) ?: bad("The condition ends too soon")

            fun expect(t: String) {
                val got = take()
                if (!got.equals(t, ignoreCase = true)) bad("Expected '$t' but found '$got'")
            }

            fun code(): String {
                val c = take()
                val reserved = c in setOf("(", ")", ",", "=") || c.lowercase() in setOf("and", "or", "not", "in")
                if (reserved) bad("Expected a code but found '$c'")
                return c.uppercase()
            }

            fun check(
                option: String,
                choices: Set<String>,
            ) {
                if (set == null) return
                val o = set.options.firstOrNull { it.code == option } ?: bad("${set.name} has no option $option")
                choices.firstOrNull { c -> o.choices.none { it.code == c } }?.let { bad("$option has no choice $it") }
            }

            lateinit var or: () -> Condition

            fun primary(): Condition {
                if (word("not")) {
                    p++
                    return Not(primary())
                }
                if (peek() == "(") {
                    p++
                    val inner = or()
                    expect(")")
                    return inner
                }
                val option = code()
                val choices =
                    if (word("in")) {
                        p++
                        expect("(")
                        val list = mutableListOf(code())
                        while (peek() == ",") {
                            p++
                            list += code()
                        }
                        expect(")")
                        list.toSet()
                    } else {
                        expect("=")
                        setOf(code())
                    }
                check(option, choices)
                return Is(option, choices)
            }

            fun and(): Condition {
                val parts = mutableListOf(primary())
                while (word("and")) {
                    p++
                    parts += primary()
                }
                return if (parts.size == 1) parts.single() else All(parts)
            }
            or = {
                val parts = mutableListOf(and())
                while (word("or")) {
                    p++
                    parts += and()
                }
                if (parts.size == 1) parts.single() else Any(parts)
            }
            val result = or()
            if (p < tokens.size) bad("Unexpected '${tokens[p]}'")
            return result
        }
    }
}
