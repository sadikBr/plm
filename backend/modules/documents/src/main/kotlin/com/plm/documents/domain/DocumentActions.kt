package com.plm.documents.domain

import com.plm.extensions.api.ActionDeclarations
import com.plm.extensions.api.ActionDefinition
import com.plm.extensions.api.ActionHandler
import com.plm.extensions.api.ActionPlacement
import org.springframework.stereotype.Component

/** Save As and Compare in every object's More menu (roadmap 3.3); the page handles them. */
@Component
internal class DocumentActions : ActionDeclarations {
    override fun actions() =
        listOf(
            ActionDefinition(
                id = "object.save-as",
                label = "Save as…",
                description = "Make a copy with a new number: attributes and files.",
                icon = "copy-plus",
                placement = ActionPlacement.OBJECT_MORE,
                order = 125,
                group = "share",
                handler = ActionHandler.BUILTIN,
            ),
            ActionDefinition(
                id = "object.compare",
                label = "Compare…",
                description = "Compare two versions of this object, or with another object.",
                icon = "git-compare",
                placement = ActionPlacement.OBJECT_MORE,
                order = 126,
                group = "share",
                handler = ActionHandler.BUILTIN,
            ),
        )
}
