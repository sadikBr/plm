package com.plm.integration.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Permission
import com.plm.bom.api.BomService
import com.plm.integration.api.Acknowledge
import com.plm.integration.api.DistributionMessage
import com.plm.integration.api.DistributionRule
import com.plm.integration.api.DistributionService
import com.plm.integration.api.DistributionTarget
import com.plm.integration.api.ObjectDistribution
import com.plm.integration.api.SaveDistributionRule
import com.plm.integration.api.SaveTarget
import com.plm.integration.api.TargetSecret
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

@Service
@Transactional
internal class DefaultDistributionService(
    private val repository: DistributionRepository,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val bom: BomService,
    private val access: AccessService,
    private val clock: Clock,
) : DistributionService {
    private val random = SecureRandom()

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators set up ERP distribution",
            )
        }
    }

    private fun target(
        tenant: TenantId,
        id: UUID,
    ) = repository.target(id)?.takeIf { it.tenant == tenant.value }
        ?: throw NotFoundException("TARGET_NOT_FOUND", "Distribution target $id does not exist", mapOf("id" to id))

    private fun view(t: TargetRow): DistributionTarget {
        val (pending, failed) = repository.counts(t.id)
        return DistributionTarget(
            t.id,
            t.name,
            t.system,
            t.plant,
            t.endpoint,
            t.attributes,
            repository.rules(t.id),
            t.active,
            pending,
            failed,
            t.createdAt,
        )
    }

    @Transactional(readOnly = true)
    override fun targets(actor: Actor): List<DistributionTarget> {
        requireAdmin(actor)
        return repository.targets(actor.tenant.value).map(::view)
    }

    private fun validate(command: SaveTarget) {
        val errors = mutableListOf<String>()
        if (command.name.isBlank() || command.name.length > 200) errors += "A name must be 1-200 characters"
        if (command.system.isBlank()) errors += "Name the ERP system"
        if (command.plant.isBlank()) errors += "Name the plant"
        command.endpoint?.takeIf { it.isNotBlank() }?.let {
            if (!it.startsWith("https://") &&
                !it.startsWith("http://")
            ) {
                errors += "The endpoint must be an http(s) address"
            }
        }
        val names = command.attributes.map { it.name.trim() }
        if (names.any { !Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$").matches(it) }) {
            errors +=
                "Plant attribute names are letters, digits and _"
        }
        if (names.size != names.toSet().size) errors += "Plant attribute names must differ"
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_TARGET",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
    }

    override fun createTarget(
        actor: Actor,
        command: SaveTarget,
    ): TargetSecret {
        requireAdmin(actor)
        validate(command)
        val secret =
            "dst_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))
        val row =
            TargetRow(
                UuidV7.generate(clock),
                actor.tenant.value,
                command.name.trim(),
                command.system.trim(),
                command.plant.trim(),
                command.endpoint?.trim()?.ifEmpty { null },
                secret,
                command.attributes.map {
                    it.copy(
                        name = it.name.trim(),
                        label = it.label.trim().ifEmpty { it.name.trim() },
                    )
                },
                command.active,
                clock.instant(),
            )
        repository.insertTarget(row)
        return TargetSecret(row.id, secret)
    }

    override fun updateTarget(
        actor: Actor,
        id: UUID,
        command: SaveTarget,
    ): DistributionTarget {
        requireAdmin(actor)
        validate(command)
        val t = target(actor.tenant, id)
        val row =
            t.copy(
                name = command.name.trim(),
                system = command.system.trim(),
                plant = command.plant.trim(),
                endpoint = command.endpoint?.trim()?.ifEmpty { null },
                attributes = command.attributes,
                active = command.active,
            )
        repository.updateTarget(row)
        return view(row)
    }

    override fun deleteTarget(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        target(actor.tenant, id)
        repository.deleteTarget(id)
    }

    override fun addRule(
        actor: Actor,
        targetId: UUID,
        command: SaveDistributionRule,
    ): DistributionTarget {
        requireAdmin(actor)
        val t = target(actor.tenant, targetId)
        types.get(actor, TypeId(command.typeId))
        repository.insertRule(
            t.id,
            DistributionRule(
                UuidV7.generate(clock),
                command.typeId,
                command.contextId,
                command.state.trim().uppercase(),
            ),
        )
        return view(t)
    }

    override fun removeRule(
        actor: Actor,
        ruleId: UUID,
    ) {
        requireAdmin(actor)
        repository.deleteRule(ruleId)
    }

    /** Rules of [targets] that apply to [obj] (its type or an ancestor, its context or any). */
    private fun matching(
        actor: Actor,
        obj: VersionedObject,
        targets: List<TargetRow>,
    ): Map<UUID, DistributionRule> {
        val lineage = types.lineage(actor, obj.typeId).map { it.value }.toSet()
        return targets
            .mapNotNull { t ->
                repository
                    .rules(t.id)
                    .firstOrNull { r ->
                        r.typeId in lineage &&
                            (r.contextId == null || r.contextId == obj.contextId.value)
                    }?.let { t.id to it }
            }.toMap()
    }

    @Transactional(readOnly = true)
    override fun status(
        actor: Actor,
        objectId: UUID,
    ): List<ObjectDistribution> {
        val obj = versioning.get(actor, MasterId(objectId))
        val targets = repository.targets(actor.tenant.value).filter { it.active }
        val rules = matching(actor, obj, targets)
        val rows = repository.objectRows(objectId).associateBy { it.targetId }
        return targets
            .filter { it.id in rules || it.id in rows }
            .map { t ->
                val r = rows[t.id]
                ObjectDistribution(
                    t.id,
                    t.name,
                    t.system,
                    t.plant,
                    r?.status ?: "NOT_SENT",
                    r?.version,
                    r?.erpKey,
                    r?.message,
                    t.attributes
                        .associate { a ->
                            a.name to (r?.plantValues?.get(a.name) ?: a.defaultValue ?: "")
                        }.filterValues { it.isNotEmpty() },
                    t.attributes,
                    rules[t.id]?.state,
                    r?.updatedAt,
                )
            }
    }

    private fun requireModify(
        actor: Actor,
        obj: VersionedObject,
    ) = access.require(
        actor,
        AccessTarget(obj.contextId, obj.typeId, obj.latest.state, obj.id.value),
        Permission.MODIFY,
    )

    override fun setPlantValues(
        actor: Actor,
        objectId: UUID,
        targetId: UUID,
        values: Map<String, String>,
    ): ObjectDistribution {
        val obj = versioning.get(actor, MasterId(objectId))
        requireModify(actor, obj)
        val t = target(actor.tenant, targetId)
        val known = t.attributes.map { it.name }.toSet()
        val clean = values.filterKeys { it in known }.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val existing = repository.objectRow(objectId, targetId)
        repository.saveObjectRow(
            (
                existing
                    ?: ObjectRow(
                        actor.tenant.value,
                        objectId,
                        targetId,
                        emptyMap(),
                        "NOT_SENT",
                        null,
                        null,
                        null,
                        clock.instant(),
                    )
            ).copy(plantValues = clean, updatedAt = clock.instant()),
        )
        return status(actor, objectId).first { it.targetId == targetId }
    }

    override fun send(
        actor: Actor,
        objectId: UUID,
        targetId: UUID,
    ): ObjectDistribution {
        val obj = versioning.get(actor, MasterId(objectId))
        requireModify(actor, obj)
        val t = target(actor.tenant, targetId)
        enqueue(actor, obj, t)
        return status(actor, objectId).first { it.targetId == targetId }
    }

    /** Puts a message for [obj]'s latest iteration in [t]'s outbox; the object's row says why if it can't. */
    private fun enqueue(
        actor: Actor,
        obj: VersionedObject,
        t: TargetRow,
    ) {
        val now = clock.instant()
        val existing =
            repository.objectRow(obj.id.value, t.id)
                ?: ObjectRow(actor.tenant.value, obj.id.value, t.id, emptyMap(), "NOT_SENT", null, null, null, now)
        val values = t.attributes.associate { a -> a.name to (existing.plantValues[a.name] ?: a.defaultValue) }
        val missing = t.attributes.filter { it.required && values[it.name].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            repository.saveObjectRow(
                existing.copy(
                    status = "ERROR",
                    version = obj.latest.version,
                    message = "Fill in ${missing.joinToString { it.label }} for ${t.plant}, then send again",
                    updatedAt = now,
                ),
            )
            return
        }
        val type = types.get(actor, obj.typeId)
        val lines =
            if (type.baseClass == BaseClass.PART) {
                bom.usages(actor, obj.id, obj.latest.id.value).map {
                    mapOf(
                        "line" to it.lineNumber,
                        "number" to it.child.number,
                        "name" to it.child.name,
                        "quantity" to it.quantity,
                        "unit" to it.unit,
                        "findNumber" to it.findNumber,
                    )
                }
            } else {
                emptyList()
            }
        val id = UuidV7.generate(clock)
        val payload =
            mapOf(
                "messageId" to id.toString(),
                "target" to mapOf("name" to t.name, "system" to t.system, "plant" to t.plant),
                "object" to
                    mapOf(
                        "id" to obj.id.value.toString(),
                        "number" to obj.number,
                        "name" to obj.name,
                        "type" to type.name,
                        "version" to obj.latest.version,
                        "state" to obj.latest.state,
                        "attributes" to obj.latest.attributes,
                    ),
                "plantValues" to values.filterValues { it != null },
                "bom" to lines,
                "acknowledge" to "/api/v1/distribution/messages/$id/ack",
            )
        repository.insertMessage(id, actor.tenant.value, t.id, obj.id.value, obj.latest.version, payload, now)
        repository.saveObjectRow(
            existing.copy(status = "PENDING", version = obj.latest.version, message = null, updatedAt = now),
        )
    }

    /** An object reached a state: send it wherever a rule says. Runs in the state change's transaction. */
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "versioning.ObjectStateChanged.v1") return
        val to = envelope.payload["to"]?.toString() ?: return
        val tenant = TenantId(envelope.tenantId)
        val targets = repository.targets(tenant.value).filter { it.active }
        if (targets.isEmpty()) return
        val system = Actor(tenant, PrincipalId("system"), siteAdmin = true)
        val obj = versioning.get(system, MasterId(envelope.aggregateId))
        val rules = matching(system, obj, targets)
        for (t in targets) {
            if (rules[t.id]?.state == to) enqueue(system, obj, t)
        }
    }

    @Transactional(readOnly = true)
    override fun messages(
        actor: Actor,
        objectId: UUID?,
        targetId: UUID?,
    ): List<DistributionMessage> {
        if (objectId != null) versioning.get(actor, MasterId(objectId)) else requireAdmin(actor)
        return repository.messages(actor.tenant.value, objectId, targetId, 200)
    }

    private fun bySecret(
        targetId: UUID,
        secret: String,
    ): TargetRow {
        val t = repository.target(targetId)
        if (t == null || !MessageDigest.isEqual(t.secret.toByteArray(), secret.toByteArray())) {
            throw ForbiddenException("INVALID_SECRET", "That secret doesn't open this target")
        }
        return t
    }

    override fun fetch(
        targetId: UUID,
        secret: String,
        limit: Int,
    ): List<DistributionMessage> {
        val t = bySecret(targetId, secret)
        val due =
            repository
                .messages(
                    t.tenant,
                    null,
                    t.id,
                    limit.coerceIn(1, 500),
                ).filter { it.status == "PENDING" }
                .reversed()
        due.forEach { repository.sent(it.id, clock.instant()) }
        due.forEach { m ->
            repository.objectRow(m.objectId, t.id)?.let { r ->
                if (r.status ==
                    "PENDING"
                ) {
                    repository.saveObjectRow(r.copy(status = "SENT", updatedAt = clock.instant()))
                }
            }
        }
        return due.map { it.copy(status = "SENT") }
    }

    override fun acknowledge(
        messageId: UUID,
        secret: String,
        answer: Acknowledge,
    ): DistributionMessage {
        val m =
            repository.message(messageId)
                ?: throw NotFoundException("MESSAGE_NOT_FOUND", "Message $messageId does not exist")
        bySecret(m.targetId, secret)
        val now = clock.instant()
        repository.answer(messageId, if (answer.ok) "ACKNOWLEDGED" else "REJECTED", answer.message, now)
        repository.objectRow(m.objectId, m.targetId)?.let { r ->
            // Only the latest message decides the object's status.
            if (r.version == m.version) {
                repository.saveObjectRow(
                    r.copy(
                        status = if (answer.ok) "ACKNOWLEDGED" else "ERROR",
                        erpKey = answer.erpKey ?: r.erpKey,
                        message = answer.message,
                        updatedAt = now,
                    ),
                )
            }
        }
        return repository.message(messageId)!!
    }
}
