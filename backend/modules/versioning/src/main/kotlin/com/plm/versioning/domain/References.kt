package com.plm.versioning.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.Permission
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeService
import com.plm.types.api.DataType
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.infra.MasterRow
import com.plm.versioning.infra.VersioningRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Reference and alias attributes (roadmap 4.12). A REFERENCE value is another object's id: it must
 * exist, be readable by whoever sets it and have the attribute's type. An alias shows a value of
 * the referenced object (`supplier.name`); it isn't stored, but read live from that object's
 * latest iteration, so it is always current, and only shown to those who can read that object.
 */
@Component
internal class References(
    private val attributes: AttributeService,
    private val types: TypeService,
    private val repository: VersioningRepository,
    private val access: AccessService,
) {
    fun aliases(
        actor: Actor,
        typeId: TypeId,
    ): List<AttributeDefinition> = attributes.effective(actor, typeId).filter { it.aliasOf != null }

    /** Checks every reference in [values]; throws `INVALID_ATTRIBUTES` listing the bad ones. */
    fun check(
        actor: Actor,
        typeId: TypeId,
        values: Map<String, Any?>,
        self: UUID?,
    ) {
        val defs = attributes.effective(actor, typeId).filter { it.dataType == DataType.REFERENCE }
        if (defs.isEmpty()) return
        val wanted = defs.associateWith { ids(values[it.name]) }.filterValues { it.isNotEmpty() }
        if (wanted.isEmpty()) return
        val found = readable(actor, wanted.values.flatten().toSet())
        val errors = mutableListOf<Map<String, Any?>>()
        for ((def, ids) in wanted) {
            val label = def.labels["en"] ?: def.name
            for (id in ids) {
                val target = found[id]
                val wantedType = def.referenceTypeId
                val problem =
                    when {
                        id == self -> "$label can't point to the object itself"
                        target == null -> "$label points to an object that doesn't exist or that you can't see"
                        wantedType != null && wantedType !in types.lineage(actor, target.first.typeId) ->
                            "$label must point to a ${types.get(actor, wantedType).displayName}"
                        else -> null
                    }
                if (problem != null) {
                    errors += mapOf("attribute" to def.name, "code" to "INVALID_REFERENCE", "message" to problem)
                }
            }
        }
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_ATTRIBUTES",
                errors.joinToString("; ") { it["message"].toString() },
                mapOf("errors" to errors),
            )
        }
    }

    /** [objects] with their alias attributes filled in from the objects they reference. */
    fun fill(
        actor: Actor,
        objects: List<VersionedObject>,
    ): List<VersionedObject> {
        if (objects.isEmpty()) return objects
        val byType = objects.map { it.typeId }.distinct().associateWith { aliases(actor, it) }
        if (byType.values.all { it.isEmpty() }) return objects
        val targetIds =
            objects
                .flatMap { o ->
                    byType.getValue(o.typeId).mapNotNull { def ->
                        ids(o.latest.attributes[def.aliasOf!!.substringBefore('.')]).firstOrNull()
                    }
                }.toSet()
        val targets = readable(actor, targetIds)
        return objects.map { o ->
            val defs = byType.getValue(o.typeId)
            if (defs.isEmpty()) o else o.copy(latest = withAliases(o.latest, defs, targets))
        }
    }

    /** [iteration] (of an object of [typeId]) with its alias attributes filled in. */
    fun fill(
        actor: Actor,
        typeId: TypeId,
        iteration: Iteration,
    ): Iteration {
        val defs = aliases(actor, typeId)
        if (defs.isEmpty()) return iteration
        val ids = defs.mapNotNull { ids(iteration.attributes[it.aliasOf!!.substringBefore('.')]).firstOrNull() }
        return withAliases(iteration, defs, readable(actor, ids.toSet()))
    }

    private fun withAliases(
        iteration: Iteration,
        defs: List<AttributeDefinition>,
        targets: Map<UUID, Pair<MasterRow, Iteration>>,
    ): Iteration {
        val values = (iteration.attributes - defs.map { it.name }.toSet()).toMutableMap()
        for (def in defs) {
            val (reference, property) = def.aliasOf!!.split('.', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val (master, latest) = ids(iteration.attributes[reference]).firstOrNull()?.let { targets[it] } ?: continue
            val value =
                when (property) {
                    "number" -> master.number
                    "name" -> master.name
                    "state" -> latest.state
                    "version" -> latest.version
                    else -> latest.attributes[property]
                }
            if (value != null) values[def.name] = value
        }
        return iteration.copy(attributes = values)
    }

    /** The referenced objects [actor] can read, by id, with their latest iteration. */
    private fun readable(
        actor: Actor,
        ids: Set<UUID>,
    ): Map<UUID, Pair<MasterRow, Iteration>> {
        if (ids.isEmpty()) return emptyMap()
        val filter =
            access.filter(
                actor,
                Permission.READ,
                AccessColumns("m.context_id", "m.type_id", "i.state", "m.id"),
            )
        return ids
            .chunked(500)
            .flatMap { chunk ->
                repository.list(actor.tenant, null, null, filter, chunk.size, null, ids = chunk)
            }.associateBy { it.first.id.value }
    }

    private fun ids(value: Any?): List<UUID> =
        when (value) {
            null -> emptyList()
            is List<*> -> value.flatMap { ids(it) }
            else -> listOfNotNull(runCatching { UUID.fromString(value.toString()) }.getOrNull())
        }
}

/** An object another one references can't be deleted: the reference would dangle (roadmap 4.12). */
@Component
internal class ReferencedObjectsStay(
    private val attributes: AttributeService,
    private val repository: VersioningRepository,
) : com.plm.versioning.api.OperationGuard {
    override fun check(
        actor: Actor,
        check: com.plm.versioning.api.OperationCheck,
    ): List<com.plm.versioning.api.Refusal> {
        if (check.operation != com.plm.versioning.api.GuardedOperation.DELETE) return emptyList()
        val id = check.objectId ?: return emptyList()
        val referrers =
            repository.referencing(
                actor.tenant,
                id,
                attributes.referenceNames(actor),
                com.plm.access.api
                    .SqlPredicate("true", emptyMap()),
                limit = 5,
            )
        if (referrers.isEmpty()) return emptyList()
        val names = referrers.map { it.first.number }.distinct()
        return listOf(
            com.plm.versioning.api.Refusal(
                "versioning.referenced-objects-stay",
                "site",
                "It's referenced by ${names.joinToString(", ")}: change those references first",
            ),
        )
    }
}
