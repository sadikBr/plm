package com.plm.versioning.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.platform.actor.Actor
import com.plm.types.api.AttributeService
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import org.springframework.stereotype.Component

/** What an expression is about: an object (as stored, or as it would be after a change). */
internal data class ObjectFacts(
    val typeId: TypeId,
    val contextId: ContextId,
    val number: String,
    val name: String,
    val state: String,
    val revision: String,
    val iteration: Int,
    val checkedOut: Boolean,
    val attributes: Map<String, Any?>,
    val owner: String? = null,
    val reservedBy: String? = null,
)

/**
 * Builds the variables expressions see (`object`, `user`; documented on
 * [com.plm.platform.expression.ExpressionModel]). Every expression in the platform gets them from
 * here, so a condition means the same wherever it's used.
 */
@Component
internal class ObjectModels(
    private val types: TypeService,
    private val attributes: AttributeService,
    private val contexts: ContextService,
    private val access: AccessService,
) {
    fun build(
        actor: Actor,
        facts: ObjectFacts,
    ): Map<String, Any?> {
        val names = types.list(actor).associate { it.id to it.name }
        val lineage = types.lineage(actor, facts.typeId).mapNotNull { names[it] }
        // Every attribute of the type is there, null when unset, so `object.attributes.x == null` works.
        val values = attributes.effective(actor, facts.typeId).associate { it.name to null } + facts.attributes
        val context = contexts.get(actor, facts.contextId)
        val permissions = access.permissions(actor, AccessTarget(facts.contextId, facts.typeId, facts.state))
        return mapOf(
            "object" to
                mapOf(
                    "number" to facts.number,
                    "name" to facts.name,
                    "type" to names[facts.typeId],
                    "types" to lineage,
                    "state" to facts.state,
                    "revision" to facts.revision,
                    "iteration" to facts.iteration,
                    "version" to "${facts.revision}.${facts.iteration}",
                    "checkedOut" to facts.checkedOut,
                    "owner" to facts.owner,
                    "reservedBy" to facts.reservedBy,
                    "attributes" to values,
                    "context" to mapOf("name" to context.name, "kind" to context.kind.name),
                ),
            "user" to
                mapOf(
                    "id" to actor.principal.value,
                    "siteAdmin" to actor.siteAdmin,
                    "permissions" to permissions.map { it.name }.sorted(),
                ),
        )
    }
}
