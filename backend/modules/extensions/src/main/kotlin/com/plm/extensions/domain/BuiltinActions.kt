package com.plm.extensions.domain

import com.plm.access.api.Permission
import com.plm.extensions.api.ActionDeclarations
import com.plm.extensions.api.ActionDefinition
import com.plm.extensions.api.ActionHandler
import com.plm.extensions.api.ActionPlacement.OBJECT_MORE
import com.plm.extensions.api.ActionPlacement.OBJECT_TOOLBAR
import org.springframework.stereotype.Component

/**
 * The platform's own object actions, declared like any extension's would be. Whether one can be
 * used on a given object (checked out? editable state?) is [BuiltinAvailability]'s job.
 */
@Component
internal class BuiltinActions : ActionDeclarations {
    override fun actions() =
        listOf(
            ActionDefinition(
                id = CHECK_OUT,
                label = "Check out",
                description = "Start editing: creates your working copy.",
                icon = "lock-open",
                placement = OBJECT_TOOLBAR,
                order = 10,
                group = "edit",
                requires = Permission.MODIFY,
            ),
            ActionDefinition(
                id = CHECK_IN,
                label = "Check in",
                description = "Save your working copy as the next iteration.",
                icon = "upload",
                placement = OBJECT_TOOLBAR,
                order = 20,
                group = "edit",
            ),
            ActionDefinition(
                id = UNDO_CHECK_OUT,
                label = "Undo check-out",
                description = "Discard your working copy.",
                icon = "undo-2",
                placement = OBJECT_TOOLBAR,
                order = 30,
                group = "edit",
            ),
            ActionDefinition(
                id = REVISE,
                label = "Revise",
                description = "Start the next revision (A.3 → B.1).",
                icon = "git-branch-plus",
                placement = OBJECT_TOOLBAR,
                order = 40,
                group = "lifecycle",
                requires = Permission.REVISE,
            ),
            ActionDefinition(
                id = SET_STATE,
                label = "Set state",
                description = "Move the object to another lifecycle state.",
                icon = "arrow-right-left",
                placement = OBJECT_TOOLBAR,
                order = 50,
                group = "lifecycle",
            ),
            ActionDefinition(
                id = COPY_NUMBER,
                label = "Copy number",
                description = "Copy the object's number to the clipboard.",
                icon = "copy",
                placement = OBJECT_MORE,
                order = 110,
                group = "share",
                handler = ActionHandler.CLIENT,
            ),
            ActionDefinition(
                id = COPY_LINK,
                label = "Copy link",
                description = "Copy a link to this object to the clipboard.",
                icon = "link-2",
                placement = OBJECT_MORE,
                order = 120,
                group = "share",
                handler = ActionHandler.CLIENT,
            ),
            ActionDefinition(
                id = RENAME,
                label = "Rename or renumber…",
                description = "Change the number or name of every version.",
                icon = "pencil-line",
                placement = OBJECT_MORE,
                order = 130,
                group = "manage",
                requires = Permission.MANAGE,
            ),
            ActionDefinition(
                id = CHANGE_LIFECYCLE,
                label = "Change lifecycle…",
                description = "Move the object to another lifecycle (site administrators).",
                icon = "workflow",
                placement = OBJECT_MORE,
                order = 140,
                group = "manage",
            ),
        )

    companion object {
        const val CHECK_OUT = "object.check-out"
        const val CHECK_IN = "object.check-in"
        const val UNDO_CHECK_OUT = "object.undo-check-out"
        const val REVISE = "object.revise"
        const val SET_STATE = "object.set-state"
        const val COPY_NUMBER = "object.copy-number"
        const val COPY_LINK = "object.copy-link"
        const val RENAME = "object.rename"
        const val CHANGE_LIFECYCLE = "object.change-lifecycle"
    }
}
