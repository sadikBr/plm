package com.plm.cad.domain

import com.plm.cad.api.CadKind
import com.plm.cad.api.FamilyColumn
import com.plm.cad.api.FamilyInstance
import com.plm.cad.api.FamilyMembership
import com.plm.cad.api.FamilyService
import com.plm.cad.api.FamilyTable
import com.plm.cad.api.NewInstance
import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.UUID

private val COLUMN_NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")

@Service
@Transactional
internal class DefaultFamilyService(
    private val jdbc: JdbcClient,
    private val cad: DefaultCadService,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val clock: java.time.Clock,
) : FamilyService {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun bad(message: String): Nothing = throw ValidationException("INVALID_FAMILY", message)

    private fun columns(iteration: UUID): List<FamilyColumn>? =
        jdbc
            .sql("select columns from cad_family where iteration_id = :i")
            .param("i", iteration)
            .query { rs, _ -> json.readValue(rs.getString(1), Array<FamilyColumn>::class.java).toList() }
            .optional()
            .orElse(null)

    private data class Row(
        val instance: UUID,
        val values: Map<String, Any?>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun rows(iteration: UUID): List<Row> =
        jdbc
            .sql("select instance_master_id, \"values\" from cad_family_row where iteration_id = :i order by position")
            .param("i", iteration)
            .query {
                rs,
                _,
                ->
                Row(
                    rs.getObject(1, UUID::class.java),
                    json.readValue(rs.getString(2), Map::class.java) as Map<String, Any?>,
                )
            }.list()

    private fun build(
        actor: Actor,
        genericId: UUID,
        iteration: UUID,
        version: String,
    ): FamilyTable {
        val rows = rows(iteration)
        val models = cad.models(actor, listOf(genericId) + rows.map { it.instance })
        return FamilyTable(
            models.getValue(genericId),
            version,
            columns(iteration).orEmpty(),
            rows.map { FamilyInstance(models.getValue(it.instance), it.values) },
        )
    }

    @Transactional(readOnly = true)
    override fun membership(
        actor: Actor,
        id: UUID,
    ): FamilyMembership {
        val o = versioning.get(actor, MasterId(id))
        if (columns(o.latest.id.value) !=
            null
        ) {
            return FamilyMembership("GENERIC", build(actor, id, o.latest.id.value, o.latest.version))
        }
        val generic =
            jdbc
                .sql(
                    """
                    select m.id from cad_family_row r join object_master m on m.latest_iteration_id = r.iteration_id
                    where r.instance_master_id = :id limit 1
                    """.trimIndent(),
                ).param("id", id)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .optional()
                .orElse(null) ?: return FamilyMembership("NONE", null)
        return FamilyMembership("INSTANCE", table(actor, generic))
    }

    @Transactional(readOnly = true)
    override fun table(
        actor: Actor,
        genericId: UUID,
        workingCopy: Boolean,
    ): FamilyTable {
        val (iteration, version) =
            if (workingCopy) {
                versioning.workingCopy(actor, MasterId(genericId)).let { it.id.value to it.version }
            } else {
                versioning.get(actor, MasterId(genericId)).latest.let { it.id.value to it.version }
            }
        return build(actor, genericId, iteration, version)
    }

    /** The generic's working copy, after checking it can have a family. */
    private fun workingCopy(
        actor: Actor,
        genericId: UUID,
    ): Pair<UUID, String> {
        val o = versioning.get(actor, MasterId(genericId))
        val kind = cadKind(types, actor, o.typeId)
        if (kind != CadKind.PART && kind != CadKind.ASSEMBLY) bad("Only CAD parts and assemblies have family tables")
        return versioning.workingCopy(actor, o.id).let { it.id.value to it.version }
    }

    override fun setColumns(
        actor: Actor,
        genericId: UUID,
        columns: List<FamilyColumn>,
    ): FamilyTable {
        val (wc, version) = workingCopy(actor, genericId)
        if (columns.isEmpty()) bad("A family table has at least one column")
        if (columns.size > 50) bad("At most 50 columns")
        columns
            .firstOrNull {
                !COLUMN_NAME.matches(it.name)
            }?.let { bad("'${it.name}' isn't a parameter name (letters, digits, _)") }
        columns
            .groupBy {
                it.name.lowercase()
            }.filterValues { it.size > 1 }
            .keys
            .firstOrNull()
            ?.let { bad("$it is there twice") }
        jdbc
            .sql(
                """
                insert into cad_family (iteration_id, tenant_id, columns) values (:i, :t, cast(:c as jsonb))
                on conflict (iteration_id) do update set columns = excluded.columns
                """.trimIndent(),
            ).param("i", wc)
            .param("t", actor.tenant.value)
            .param("c", json.writeValueAsString(columns))
            .update()
        return build(actor, genericId, wc, version)
    }

    private fun checkedValues(
        wc: UUID,
        values: Map<String, Any?>,
    ): Map<String, Any?> {
        val known = (columns(wc) ?: bad("Add the table's columns first")).map { it.name }.toSet()
        values.keys.firstOrNull { it !in known }?.let { bad("$it isn't a column of the table") }
        return values.filterValues { it != null && it != "" }
    }

    override fun addInstance(
        actor: Actor,
        genericId: UUID,
        instance: NewInstance,
    ): FamilyTable {
        val (wc, version) = workingCopy(actor, genericId)
        val values = checkedValues(wc, instance.values)
        val generic = versioning.get(actor, MasterId(genericId))
        val shared = generic.latest.attributes.filterKeys { it == "authoringApplication" || it == "fileFormat" }
        val created =
            versioning.create(
                actor,
                CreateVersionedObject(
                    generic.typeId,
                    generic.contextId,
                    instance.name,
                    instance.number
                        ?.trim()
                        ?.ifEmpty {
                            null
                        },
                    shared,
                ),
            )
        jdbc
            .sql(
                """
                insert into cad_family_row (id, tenant_id, iteration_id, instance_master_id, "values", position)
                values (:id, :t, :i, :m, cast(:v as jsonb),
                        (select coalesce(max(position), 0) + 10 from cad_family_row where iteration_id = :i))
                """.trimIndent(),
            ).param("id", UuidV7.generate(clock))
            .param("t", actor.tenant.value)
            .param("i", wc)
            .param("m", created.id.value)
            .param("v", json.writeValueAsString(values))
            .update()
        return build(actor, genericId, wc, version)
    }

    override fun setValues(
        actor: Actor,
        genericId: UUID,
        instanceId: UUID,
        values: Map<String, Any?>,
    ): FamilyTable {
        val (wc, version) = workingCopy(actor, genericId)
        val checked = checkedValues(wc, values)
        val n =
            jdbc
                .sql(
                    "update cad_family_row set \"values\" = cast(:v as jsonb) where iteration_id = :i and instance_master_id = :m",
                ).param("v", json.writeValueAsString(checked))
                .param("i", wc)
                .param("m", instanceId)
                .update()
        if (n == 0) throw NotFoundException("INSTANCE_NOT_FOUND", "It isn't an instance of this table")
        return build(actor, genericId, wc, version)
    }

    override fun removeInstance(
        actor: Actor,
        genericId: UUID,
        instanceId: UUID,
    ): FamilyTable {
        val (wc, version) = workingCopy(actor, genericId)
        val n =
            jdbc
                .sql("delete from cad_family_row where iteration_id = :i and instance_master_id = :m")
                .param("i", wc)
                .param("m", instanceId)
                .update()
        if (n == 0) throw NotFoundException("INSTANCE_NOT_FOUND", "It isn't an instance of this table")
        return build(actor, genericId, wc, version)
    }
}
