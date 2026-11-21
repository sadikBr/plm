package com.plm.documents.domain

import com.plm.access.api.Permission
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
            // Organizing (roadmap 3.4).
            ActionDefinition(
                id = "object.add-to-clipboard",
                label = "Add to clipboard",
                description = "Keep it at hand to paste into a folder: moved or copied.",
                icon = "clipboard-plus",
                placement = ActionPlacement.OBJECT_MORE,
                order = 127,
                group = "share",
                handler = ActionHandler.CLIENT,
            ),
            ActionDefinition(
                id = "object.reserve",
                label = "Reserve",
                description = "Keep others from checking it out until you release it.",
                icon = "bookmark",
                placement = ActionPlacement.OBJECT_MORE,
                order = 105,
                group = "edit",
                requires = Permission.MODIFY,
                visibleWhen = "object.reservedBy == null && !object.checkedOut",
            ),
            ActionDefinition(
                id = "object.release",
                label = "Release reservation",
                description = "Let others check it out again.",
                icon = "bookmark-x",
                placement = ActionPlacement.OBJECT_MORE,
                order = 106,
                group = "edit",
                visibleWhen = "object.reservedBy != null && (object.reservedBy == user.id || user.siteAdmin)",
            ),
            ActionDefinition(
                id = "object.move",
                label = "Move…",
                description = "Put it in another folder or context.",
                icon = "folder-input",
                placement = ActionPlacement.OBJECT_MORE,
                order = 132,
                group = "manage",
                requires = Permission.MODIFY,
            ),
            ActionDefinition(
                id = "object.change-owner",
                label = "Change owner…",
                description = "Hand it over to someone else.",
                icon = "user-round-cog",
                placement = ActionPlacement.OBJECT_MORE,
                order = 133,
                group = "manage",
            ),
            ActionDefinition(
                id = "object.delete",
                label = "Delete…",
                description = "Remove it and all its versions, if the delete rules allow.",
                icon = "trash-2",
                placement = ActionPlacement.OBJECT_MORE,
                order = 200,
                group = "danger",
            ),
        )
}
