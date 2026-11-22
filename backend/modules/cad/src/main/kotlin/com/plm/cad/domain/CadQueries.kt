package com.plm.cad.domain

import com.plm.query.api.FieldSpec
import com.plm.query.api.FieldType
import com.plm.query.api.FieldValue
import com.plm.query.api.QueryContributor
import com.plm.query.api.RelationSpec
import org.springframework.stereotype.Component

/** Parts and their CAD models, for the report builder. */
@Component
internal class CadQueries : QueryContributor {
    private val kind =
        listOf(
            FieldSpec(
                "kind",
                "Association",
                FieldType.ENUM,
                "{l}.kind",
                listOf(FieldValue("OWNER", "Owner"), FieldValue("CONTRIBUTING", "Contributing")),
            ),
        )

    override fun relations() =
        listOf(
            RelationSpec(
                key = "cadModels",
                label = "CAD models",
                from = setOf("PART"),
                to = setOf("DOCUMENT"),
                table = "cad_association",
                on = "{l}.part_iteration_id = {fi}.id",
                toMaster = "{l}.cad_master_id",
                fields = kind,
            ),
            RelationSpec(
                key = "cadParts",
                label = "Parts of the CAD model",
                from = setOf("DOCUMENT"),
                to = setOf("PART"),
                table = "cad_association",
                on = "{l}.cad_master_id = {fm}.id",
                toIteration = "{l}.part_iteration_id",
                fields = kind,
            ),
        )
}
