package com.plm.change.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.Permission
import com.plm.change.api.ActiveVariance
import com.plm.change.api.AssociationRule
import com.plm.change.api.ChangeFrom
import com.plm.change.api.ChangeKind
import com.plm.change.api.ChangeRole
import com.plm.change.api.ChangeSetupService
import com.plm.change.api.ChangeTemplate
import com.plm.change.api.SaveAssociationRule
import com.plm.change.api.SaveChangeTemplate
import com.plm.change.api.UseChangeTemplate
import com.plm.change.api.VarianceKind
import com.plm.change.api.VarianceScope
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Service
@Transactional
internal class DefaultChangeSetupService(
    private val jdbc: JdbcClient,
    private val types: TypeService,
    private val versioning: VersioningService,
    private val access: AccessService,
    private val changes: DefaultChangeService,
    private val clock: Clock,
) : ChangeSetupService {
    private val json = JsonMapper.builder().build()

    private fun admin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators set up change management",
            )
        }
    }

    // ---------- Templates ----------

    @Transactional(readOnly = true)
    override fun templates(actor: Actor): List<ChangeTemplate> =
        jdbc
            .sql("select * from change_template where tenant_id = :t order by lower(name)")
            .param("t", actor.tenant.value)
            .query { rs, _ ->
                ChangeTemplate(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("name"),
                    rs.getObject("type_id", UUID::class.java),
                    rs.getString("title"),
                    rs.getString("description"),
                    json.readValue(rs.getString("tasks"), List::class.java).map { it.toString() },
                    rs.getBoolean("active"),
                )
            }.list()

    override fun saveTemplate(
        actor: Actor,
        id: UUID?,
        request: SaveChangeTemplate,
    ): ChangeTemplate {
        admin(actor)
        val name = request.name.trim()
        if (name.isEmpty()) throw ValidationException("INVALID_CHANGE_TEMPLATE", "Give the template a name")
        if (changeKind(types, actor, TypeId(request.typeId)) == null) {
            throw ValidationException("INVALID_CHANGE_TEMPLATE", "A change template creates change objects")
        }
        val key = id ?: UuidV7.generate(clock)
        try {
            jdbc
                .sql(
                    """
                    insert into change_template (id, tenant_id, name, type_id, title, description, tasks, active, updated_at)
                    values (:id, :t, :name, :type, :title, :description, cast(:tasks as jsonb), :active, :at)
                    on conflict (id) do update set name = excluded.name, type_id = excluded.type_id, title = excluded.title,
                        description = excluded.description, tasks = excluded.tasks, active = excluded.active,
                        updated_at = excluded.updated_at
                    """.trimIndent(),
                ).param("id", key)
                .param("t", actor.tenant.value)
                .param("name", name)
                .param("type", request.typeId)
                .param("title", request.title?.trim()?.ifEmpty { null })
                .param("description", request.description?.trim()?.ifEmpty { null })
                .param("tasks", json.writeValueAsString(request.tasks.map { it.trim() }.filter { it.isNotEmpty() }))
                .param("active", request.active)
                .param("at", Timestamp.from(clock.instant()))
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("DUPLICATE_CHANGE_TEMPLATE", "There's already a template named $name")
        }
        return templates(actor).first { it.id == key }
    }

    override fun deleteTemplate(
        actor: Actor,
        id: UUID,
    ) {
        admin(actor)
        jdbc
            .sql(
                "delete from change_template where id = :id and tenant_id = :t",
            ).param("id", id)
            .param("t", actor.tenant.value)
            .update()
    }

    override fun useTemplate(
        actor: Actor,
        id: UUID,
        request: UseChangeTemplate,
    ): UUID {
        val template =
            templates(actor).firstOrNull { it.id == id && it.active }
                ?: throw NotFoundException("CHANGE_TEMPLATE_NOT_FOUND", "There's no such change template")
        val name = request.name?.trim()?.ifEmpty { null } ?: template.title ?: template.name
        val change =
            versioning.create(
                actor,
                CreateVersionedObject(TypeId(template.typeId), ContextId(request.contextId), name),
            )
        if (changeKind(types, actor, TypeId(template.typeId)) == ChangeKind.CHANGE_NOTICE) {
            template.tasks.forEach {
                changes.createFrom(
                    actor,
                    ChangeFrom(change.id.value, it, ChangeKind.CHANGE_TASK, affected = emptyList()),
                )
            }
        }
        return change.id.value
    }

    // ---------- Association rules ----------

    @Transactional(readOnly = true)
    override fun rules(actor: Actor): List<AssociationRule> =
        jdbc
            .sql("select * from change_association_rule where tenant_id = :t order by created_at")
            .param("t", actor.tenant.value)
            .query { rs, _ ->
                AssociationRule(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("change_type_id", UUID::class.java),
                    ChangeRole.valueOf(rs.getString("role")),
                    rs.getObject("target_type_id", UUID::class.java),
                )
            }.list()

    override fun addRule(
        actor: Actor,
        request: SaveAssociationRule,
    ): AssociationRule {
        admin(actor)
        if (changeKind(types, actor, TypeId(request.changeTypeId)) == null &&
            request.changeTypeId != TypeId.CHANGE.value
        ) {
            throw ValidationException("INVALID_RULE", "Rules are for change types")
        }
        types.get(actor, TypeId(request.targetTypeId))
        val id = UuidV7.generate(clock)
        try {
            jdbc
                .sql(
                    """
                    insert into change_association_rule (id, tenant_id, change_type_id, role, target_type_id, created_at)
                    values (:id, :t, :c, :r, :target, :at)
                    """.trimIndent(),
                ).param("id", id)
                .param("t", actor.tenant.value)
                .param("c", request.changeTypeId)
                .param("r", request.role.name)
                .param("target", request.targetTypeId)
                .param("at", Timestamp.from(clock.instant()))
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("DUPLICATE_RULE", "That rule exists already")
        }
        return rules(actor).first { it.id == id }
    }

    override fun deleteRule(
        actor: Actor,
        id: UUID,
    ) {
        admin(actor)
        jdbc
            .sql(
                "delete from change_association_rule where id = :id and tenant_id = :t",
            ).param("id", id)
            .param("t", actor.tenant.value)
            .update()
    }

    // ---------- Variances ----------

    @Transactional(readOnly = true)
    override fun variance(
        actor: Actor,
        changeId: UUID,
    ): VarianceScope? {
        versioning.get(actor, MasterId(changeId))
        return scope(changeId)
    }

    private fun scope(changeId: UUID): VarianceScope? =
        jdbc
            .sql("select * from variance where change_id = :id")
            .param("id", changeId)
            .query { rs, _ ->
                VarianceScope(
                    VarianceKind.valueOf(rs.getString("kind")),
                    rs.getObject("valid_until", LocalDate::class.java),
                    rs.getObject("quantity") as Int?,
                    rs.getString("reason"),
                )
            }.optional()
            .orElse(null)

    override fun setVariance(
        actor: Actor,
        changeId: UUID,
        scope: VarianceScope,
    ): VarianceScope {
        val o = versioning.get(actor, MasterId(changeId))
        if (changeKind(types, actor, o.typeId) !=
            ChangeKind.VARIANCE
        ) {
            throw ValidationException("NOT_A_VARIANCE", "${o.number} isn't a variance")
        }
        access.require(actor, AccessTarget(o.contextId, o.typeId, o.latest.state, o.id.value), Permission.MODIFY)
        if (!versioning.lifecycle(actor, o.id).editable) {
            throw ConflictException(
                "CHANGE_NOT_EDITABLE",
                "${o.number} is ${o.latest.state}: its scope can't change now",
            )
        }
        if (scope.validUntil == null && scope.quantity == null) {
            throw ValidationException("INVALID_VARIANCE", "Limit it: until a date, for a quantity, or both")
        }
        jdbc
            .sql(
                """
                insert into variance (change_id, tenant_id, kind, valid_until, quantity, reason)
                values (:id, :t, :kind, :until, :qty, :reason)
                on conflict (change_id) do update set kind = excluded.kind, valid_until = excluded.valid_until,
                    quantity = excluded.quantity, reason = excluded.reason
                """.trimIndent(),
            ).param("id", changeId)
            .param("t", actor.tenant.value)
            .param("kind", scope.kind.name)
            .param("until", scope.validUntil)
            .param("qty", scope.quantity)
            .param("reason", scope.reason?.trim()?.ifEmpty { null })
            .update()
        return scope(changeId)!!
    }

    @Transactional(readOnly = true)
    override fun activeVariances(
        actor: Actor,
        objectId: UUID,
    ): List<ActiveVariance> {
        versioning.get(actor, MasterId(objectId))
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val ids = changes.rows(targetId = objectId).filter { it.role == ChangeRole.AFFECTED }.map { it.changeId }
        if (ids.isEmpty()) return emptyList()
        return versioning
            .list(actor, ObjectQuery(ids = ids.map(::MasterId), limit = 500))
            .filter { changeKind(types, actor, it.typeId) == ChangeKind.VARIANCE && it.latest.state == "APPROVED" }
            .mapNotNull { v ->
                val s = scope(v.id.value) ?: return@mapNotNull null
                if (s.validUntil != null && s.validUntil.isBefore(today)) return@mapNotNull null
                ActiveVariance(v.id.value, v.number, v.name, s)
            }
    }
}
