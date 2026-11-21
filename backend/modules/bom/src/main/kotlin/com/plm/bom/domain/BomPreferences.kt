package com.plm.bom.domain

import com.plm.preferences.api.PreferenceDeclarations
import com.plm.preferences.api.PreferenceDefinition
import com.plm.preferences.api.PreferenceLevel
import com.plm.preferences.api.PreferenceOption
import com.plm.preferences.api.PreferenceType
import org.springframework.stereotype.Component

/** Which iterations structures show by default (roadmap 4.2). */
@Component
internal class BomPreferences : PreferenceDeclarations {
    override fun preferences() =
        listOf(
            PreferenceDefinition(
                key = "bom.configSpec",
                label = "Structure shows",
                description = "Which version of each part a structure shows by default. People can change it while they look.",
                category = "Structure",
                type = PreferenceType.CHOICE,
                default = "LATEST",
                levels = setOf(PreferenceLevel.SITE, PreferenceLevel.CONTEXT, PreferenceLevel.USER),
                options = {
                    listOf(
                        PreferenceOption("LATEST", "The latest version"),
                        PreferenceOption("RELEASED", "The latest released version"),
                    )
                },
            ),
        )
}
