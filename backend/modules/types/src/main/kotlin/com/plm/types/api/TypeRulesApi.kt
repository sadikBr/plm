package com.plm.types.api

import com.plm.platform.actor.Actor
import java.util.UUID

/**
 * How revisions are labelled. Chosen per type, then stored on each object at creation, so
 * changing a type's scheme later never relabels existing objects.
 */
enum class RevisionScheme(
    private val alphabet: String?,
    val first: String,
) {
    /** A, B … Y, AA, AB …, skipping I, O, Q, S, X and Z (easily confused with digits; ASME Y14.35). */
    ALPHABETIC_ASME("ABCDEFGHJKLMNPRTUVWY", "A"),

    /** A, B … Z, AA, AB … */
    ALPHABETIC("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "A"),

    /** 1, 2, 3 … */
    NUMERIC_FROM_1(null, "1"),

    /** 0, 1, 2 … (e.g. for prototypes that start at revision 0) */
    NUMERIC_FROM_0(null, "0"),
    ;

    fun isValid(revision: String): Boolean =
        when (alphabet) {
            null ->
                revision.isNotEmpty() &&
                    revision.all { it.isDigit() } &&
                    (revision == "0" || !revision.startsWith("0"))
            else -> revision.isNotEmpty() && revision.all { it in alphabet }
        }

    fun next(revision: String): String {
        require(isValid(revision)) { "'$revision' is not a valid $name revision" }
        if (alphabet == null) return (revision.toBigInteger() + java.math.BigInteger.ONE).toString()
        // Bijective base-N increment: …Y -> …AA, YY -> AAA.
        val chars = revision.toCharArray()
        for (i in chars.indices.reversed()) {
            val pos = alphabet.indexOf(chars[i])
            if (pos < alphabet.lastIndex) {
                chars[i] = alphabet[pos + 1]
                return String(chars)
            }
            chars[i] = alphabet.first()
        }
        return alphabet.first() + String(chars)
    }
}

@JvmInline
value class NumberingSchemeId(
    val value: UUID,
) {
    override fun toString() = value.toString()
}

/** Generates numbers like `PRT-000042-A`: prefix + zero-padded counter + suffix. */
data class NumberingScheme(
    val id: NumberingSchemeId,
    val name: String,
    val prefix: String,
    val padding: Int,
    val suffix: String,
    /** The number the next object will get (counter value, before formatting). */
    val nextValue: Long,
) {
    fun format(value: Long): String = prefix + value.toString().padStart(padding, '0') + suffix

    val example: String get() = format(nextValue)
}

data class CreateNumberingScheme(
    val name: String,
    val prefix: String = "",
    val padding: Int = 6,
    val suffix: String = "",
    val start: Long = 1,
)

/** Rules that apply when objects of a type are created and revised. Inherited by subtypes unless overridden. */
data class TypeRules(
    val typeId: TypeId,
    /** Null: the system-wide 10-digit sequence. */
    val numberingSchemeId: NumberingSchemeId?,
    val revisionScheme: RevisionScheme,
    /** Whether users may type their own number instead of taking the next generated one. */
    val allowManualNumber: Boolean,
    /** For each rule, the type it comes from (itself, or the ancestor it was inherited from). */
    val inheritedFrom: Map<String, TypeId>,
)

/** Overrides on one type. Null means "inherit from the parent type". */
data class TypeRuleSettings(
    val numberingSchemeId: NumberingSchemeId? = null,
    val revisionScheme: RevisionScheme? = null,
    val allowManualNumber: Boolean? = null,
)

/**
 * Numbering schemes and per-type initialization rules (roadmap 1.5). Changing them is for site
 * administrators. Error codes: `ADMIN_REQUIRED`, `TYPE_NOT_FOUND`, `NUMBERING_SCHEME_NOT_FOUND`,
 * `INVALID_NUMBERING_SCHEME`, `DUPLICATE_NUMBERING_SCHEME`.
 */
interface TypeRuleService {
    fun rules(
        actor: Actor,
        typeId: TypeId,
    ): TypeRules

    fun updateRules(
        actor: Actor,
        typeId: TypeId,
        settings: TypeRuleSettings,
    ): TypeRules

    fun numberingSchemes(actor: Actor): List<NumberingScheme>

    fun createNumberingScheme(
        actor: Actor,
        command: CreateNumberingScheme,
    ): NumberingScheme

    /** Takes the next number from [scheme]. Gap-free within committed transactions; concurrent callers serialize on the counter. */
    fun nextNumber(
        actor: Actor,
        scheme: NumberingSchemeId,
    ): String
}
