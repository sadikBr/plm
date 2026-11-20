package com.plm.versioning.domain

import com.plm.preferences.api.PreferenceDeclarations
import com.plm.preferences.api.PreferenceDefinition
import com.plm.preferences.api.PreferenceLevel
import com.plm.preferences.api.PreferenceOption
import com.plm.preferences.api.PreferenceType
import com.plm.types.api.TypeRuleService
import org.springframework.stereotype.Component

internal const val CHECK_IN_COMMENT_REQUIRED = "versioning.checkInCommentRequired"
internal const val DEFAULT_NUMBERING_SCHEME = "numbering.defaultScheme"

@Component
internal class VersioningPreferences(
    private val rules: TypeRuleService,
) : PreferenceDeclarations {
    override fun preferences() =
        listOf(
            PreferenceDefinition(
                key = CHECK_IN_COMMENT_REQUIRED,
                label = "Check-in comment required",
                description = "Users must say what changed when they check in.",
                category = "Versioning",
                type = PreferenceType.BOOLEAN,
                default = false,
                levels = setOf(PreferenceLevel.SITE, PreferenceLevel.CONTEXT),
            ),
            PreferenceDefinition(
                key = DEFAULT_NUMBERING_SCHEME,
                label = "Numbering scheme",
                description =
                    "Numbers new objects in this context whose type doesn't set its own numbering scheme " +
                        "(instead of the system's 10-digit sequence).",
                category = "Numbering",
                type = PreferenceType.CHOICE,
                default = null,
                levels = setOf(PreferenceLevel.SITE, PreferenceLevel.CONTEXT),
                options = { actor ->
                    rules.numberingSchemes(actor).map { PreferenceOption(it.name, "${it.name} (next ${it.example})") }
                },
            ),
        )
}
