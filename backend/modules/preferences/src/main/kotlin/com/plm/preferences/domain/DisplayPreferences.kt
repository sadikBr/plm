package com.plm.preferences.domain

import com.plm.preferences.api.PreferenceDeclarations
import com.plm.preferences.api.PreferenceDefinition
import com.plm.preferences.api.PreferenceLevel
import com.plm.preferences.api.PreferenceType
import org.springframework.stereotype.Component

/** Preferences of the user interface itself. */
@Component
internal class DisplayPreferences : PreferenceDeclarations {
    override fun preferences() =
        listOf(
            PreferenceDefinition(
                key = "ui.tablePageSize",
                label = "Rows per page",
                description = "How many objects tables load at a time.",
                category = "Display",
                type = PreferenceType.INTEGER,
                default = 50,
                levels = setOf(PreferenceLevel.SITE, PreferenceLevel.CONTEXT, PreferenceLevel.USER),
                min = 10,
                max = 500,
            ),
            PreferenceDefinition(
                key = "ui.showOrganization",
                label = "Show organization names",
                description =
                    "Show which organization a product, library or project belongs to, " +
                        "next to its name in the navigator and lists.",
                category = "Display",
                type = PreferenceType.BOOLEAN,
                default = true,
                levels = setOf(PreferenceLevel.SITE, PreferenceLevel.USER),
            ),
        )
}
