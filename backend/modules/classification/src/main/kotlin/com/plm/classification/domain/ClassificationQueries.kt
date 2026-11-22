package com.plm.classification.domain

import com.plm.classification.api.ClassDataType
import com.plm.classification.api.ClassificationService
import com.plm.platform.actor.Actor
import com.plm.query.api.FieldSpec
import com.plm.query.api.FieldType
import com.plm.query.api.FieldValue
import com.plm.query.api.QueryContributor
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

private val KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,62}$")

/** A part's class and its class attributes, for the report builder. */
@Component
internal class ClassificationQueries(
    @Lazy private val classification: ClassificationService,
) : QueryContributor {
    override fun fields(actor: Actor): List<FieldSpec> {
        val tree = classification.tree(actor)
        val classField =
            FieldSpec(
                "class",
                "Class",
                FieldType.ENUM,
                "(select cn.code from class_value cv join class_node cn on cn.id = cv.class_id where cv.iteration_id = {i}.id)",
                tree.map { FieldValue(it.code, it.name) },
            )
        // A key used by several classes is one field: its value is whichever class the part is in.
        val attributes =
            tree
                .flatMap { it.attributes }
                .filter { KEY.matches(it.key) }
                .groupBy { it.key }
                .map { (key, defs) ->
                    val a = defs.first()
                    val value = "(select cv.\"values\"->>'$key' from class_value cv where cv.iteration_id = {i}.id)"
                    val json =
                        "(select jsonb_typeof(cv.\"values\"->'$key') from class_value cv " +
                            "where cv.iteration_id = {i}.id)"
                    when (a.dataType) {
                        ClassDataType.NUMBER ->
                            FieldSpec(
                                "class:$key",
                                "Class: ${a.label}",
                                FieldType.NUMBER,
                                "(case when $json = 'number' then $value::numeric end)",
                                unit = a.unit,
                            )
                        ClassDataType.BOOLEAN ->
                            FieldSpec(
                                "class:$key",
                                "Class: ${a.label}",
                                FieldType.BOOLEAN,
                                "(case when $json = 'boolean' then $value::boolean end)",
                            )
                        ClassDataType.ENUM ->
                            FieldSpec(
                                "class:$key",
                                "Class: ${a.label}",
                                FieldType.ENUM,
                                value,
                                defs.flatMap { it.allowed }.distinct().map { FieldValue(it, it) },
                            )
                        ClassDataType.TEXT -> FieldSpec("class:$key", "Class: ${a.label}", FieldType.TEXT, value)
                    }
                }
        return listOf(classField) + attributes
    }
}
