package com.plm.versioning.domain

/**
 * Alphabetic revision letters, skipping I, O, Q, S, X and Z, which are easily confused with
 * digits (the ASME Y14.35 convention): A, B ... Y, then AA, AB ... YY, then AAA.
 *
 * Revision schemes will become configurable per type. This is the default.
 */
internal object RevisionSeries {
    private const val LETTERS = "ABCDEFGHJKLMNPRTUVWY"

    val first: String = LETTERS.first().toString()

    fun isValid(revision: String) = revision.isNotEmpty() && revision.all { it in LETTERS }

    fun next(revision: String): String {
        require(isValid(revision)) { "Not a valid revision: '$revision'" }
        val chars = revision.toCharArray()
        for (i in chars.indices.reversed()) {
            val pos = LETTERS.indexOf(chars[i])
            if (pos < LETTERS.lastIndex) {
                chars[i] = LETTERS[pos + 1]
                return String(chars)
            }
            chars[i] = LETTERS.first()
        }
        return LETTERS.first() + String(chars)
    }
}
