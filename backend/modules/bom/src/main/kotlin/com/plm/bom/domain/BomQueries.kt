package com.plm.bom.domain

import com.plm.query.api.FieldSpec
import com.plm.query.api.FieldType
import com.plm.query.api.FieldValue
import com.plm.query.api.QueryContributor
import com.plm.query.api.RelationSpec
import org.springframework.stereotype.Component

/** Structure and part–document links, for the report builder. */
@Component
internal class BomQueries : QueryContributor {
    private val line =
        listOf(
            FieldSpec("quantity", "Quantity", FieldType.NUMBER, "{l}.quantity"),
            FieldSpec("unit", "Unit", FieldType.TEXT, "{l}.unit"),
            FieldSpec("lineNumber", "Line", FieldType.NUMBER, "{l}.line_number"),
            FieldSpec("findNumber", "Find number", FieldType.TEXT, "{l}.find_number"),
            FieldSpec("notes", "Notes", FieldType.TEXT, "{l}.notes"),
        )
    private val kind =
        listOf(
            FieldSpec(
                "kind",
                "Link kind",
                FieldType.ENUM,
                "{l}.kind",
                listOf(FieldValue("DESCRIBED_BY", "Described by"), FieldValue("REFERENCES", "References")),
            ),
        )

    override fun relations() =
        listOf(
            RelationSpec(
                key = "uses",
                label = "Uses (structure)",
                from = setOf("PART"),
                to = setOf("PART"),
                table = "usage_link",
                on = "{l}.parent_iteration_id = {fi}.id",
                toMaster = "{l}.child_master_id",
                fields = line,
            ),
            RelationSpec(
                key = "usedBy",
                label = "Used by (where used)",
                from = setOf("PART"),
                to = setOf("PART"),
                table = "usage_link",
                on = "{l}.child_master_id = {fm}.id",
                toIteration = "{l}.parent_iteration_id",
                fields = line,
            ),
            RelationSpec(
                key = "documents",
                label = "Documents",
                from = setOf("PART"),
                to = setOf("DOCUMENT"),
                table = "part_document_link",
                on = "{l}.part_iteration_id = {fi}.id",
                toMaster = "{l}.document_master_id",
                fields = kind,
            ),
            RelationSpec(
                key = "documentedParts",
                label = "Parts it documents",
                from = setOf("DOCUMENT"),
                to = setOf("PART"),
                table = "part_document_link",
                on = "{l}.document_master_id = {fm}.id",
                toIteration = "{l}.part_iteration_id",
                fields = kind,
            ),
        )
}
