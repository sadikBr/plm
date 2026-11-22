package com.plm.versioning.domain

import com.plm.query.api.FieldSpec
import com.plm.query.api.FieldType
import com.plm.query.api.ObjectSource
import com.plm.query.api.QueryContributor
import com.plm.query.api.VersionMode
import org.springframework.stereotype.Component

/** How the report builder reads versioned objects: their tables, version picks and built-in fields. */
@Component
internal class VersioningQueries : QueryContributor {
    override fun objectSource() =
        ObjectSource(
            masterTable = "object_master",
            iterationTable = "object_iteration",
            masterOfIteration = "{i}.master_id",
            picks =
                mapOf(
                    VersionMode.LATEST to "{i}.id = {m}.latest_iteration_id",
                    VersionMode.STATE to
                        "{i}.id = (select x.id from object_iteration x where x.master_id = {m}.id " +
                        "and x.status = 'CHECKED_IN' and x.one_off is null and x.state = :q_state " +
                        "order by x.seq desc limit 1)",
                    VersionMode.AS_OF to
                        "{i}.id = (select x.id from object_iteration x where x.master_id = {m}.id " +
                        "and x.status = 'CHECKED_IN' and x.one_off is null and x.checked_in_at <= :q_as_of " +
                        "order by x.seq desc limit 1)",
                ),
            tenant = "{m}.tenant_id = :q_tenant",
            typeId = "{m}.type_id",
            contextId = "{m}.context_id",
            state = "{i}.state",
            id = "{m}.id",
            attributes = "{i}.attrs",
            fields =
                listOf(
                    FieldSpec("number", "Number", FieldType.TEXT, "{m}.number"),
                    FieldSpec("name", "Name", FieldType.TEXT, "{m}.name"),
                    FieldSpec("type", "Type", FieldType.TYPE, "{m}.type_id"),
                    FieldSpec("context", "Container", FieldType.CONTEXT, "{m}.context_id"),
                    FieldSpec("state", "State", FieldType.STATE, "{i}.state"),
                    FieldSpec("version", "Version", FieldType.TEXT, "({i}.revision || '.' || {i}.iteration)"),
                    FieldSpec("revision", "Revision", FieldType.TEXT, "{i}.revision"),
                    FieldSpec("owner", "Owner", FieldType.PRINCIPAL, "coalesce({m}.owner, {m}.created_by)"),
                    FieldSpec("createdBy", "Created by", FieldType.PRINCIPAL, "{m}.created_by"),
                    FieldSpec("created", "Created", FieldType.DATE, "{m}.created_at"),
                    FieldSpec(
                        "modified",
                        "Last changed",
                        FieldType.DATE,
                        "coalesce({i}.checked_in_at, {i}.created_at)",
                    ),
                    FieldSpec("modifiedBy", "Last changed by", FieldType.PRINCIPAL, "{i}.created_by"),
                    FieldSpec(
                        "checkedOutBy",
                        "Checked out by",
                        FieldType.PRINCIPAL,
                        "(select w.checked_out_by from object_iteration w where w.master_id = {m}.id and w.status = 'WORKING')",
                    ),
                ),
        )
}
