package com.plm.change.domain

import com.plm.query.api.FieldSpec
import com.plm.query.api.FieldType
import com.plm.query.api.FieldValue
import com.plm.query.api.QueryContributor
import com.plm.query.api.RelationSpec
import org.springframework.stereotype.Component

/** Changes and what they're about, for the report builder. */
@Component
internal class ChangeQueries : QueryContributor {
    private val link =
        listOf(
            FieldSpec(
                "role",
                "Role",
                FieldType.ENUM,
                "{l}.role",
                listOf(
                    FieldValue("AFFECTED", "Affected"),
                    FieldValue("RESULTING", "Resulting"),
                    FieldValue("END_ITEM", "End item"),
                    FieldValue("ADDRESSES", "Addresses"),
                    FieldValue("IMPLEMENTS", "Implements"),
                    FieldValue("TASK", "Task"),
                ),
            ),
            FieldSpec("note", "Note", FieldType.TEXT, "{l}.note"),
        )

    override fun relations() =
        listOf(
            RelationSpec(
                key = "changeItems",
                label = "Change's objects (affected, resulting, …)",
                from = setOf("CHANGE"),
                table = "change_link",
                on = "{l}.change_id = {fm}.id",
                toMaster = "{l}.target_id",
                fields = link,
            ),
            RelationSpec(
                key = "changes",
                label = "Changes about it",
                to = setOf("CHANGE"),
                table = "change_link",
                on = "{l}.target_id = {fm}.id",
                toMaster = "{l}.change_id",
                fields = link,
            ),
        )
}
