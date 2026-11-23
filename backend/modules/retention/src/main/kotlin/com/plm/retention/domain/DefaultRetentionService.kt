package com.plm.retention.domain

import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.bom.api.BomService
import com.plm.bom.api.DocumentLinkService
import com.plm.jobs.api.Job
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobService
import com.plm.jobs.api.SubmitJob
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.retention.api.Archive
import com.plm.retention.api.LegalHold
import com.plm.retention.api.RetentionAction
import com.plm.retention.api.RetentionRule
import com.plm.retention.api.RetentionService
import com.plm.retention.api.SaveLegalHold
import com.plm.retention.api.SaveRetentionRule
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.PurgeService
import com.plm.versioning.api.Refusal
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
internal class DefaultRetentionService(
    private val repository: RetentionRepository,
    private val versioning: VersioningService,
    private val purge: PurgeService,
    private val contexts: ContextService,
    private val types: TypeService,
    private val bom: BomService,
    private val links: DocumentLinkService,
    private val archiver: Archiver,
    private val jobs: JobService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : RetentionService {
    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators manage retention and legal holds",
            )
        }
    }

    override fun rules(actor: Actor): List<RetentionRule> {
        requireAdmin(actor)
        return repository.rules(actor.tenant.value)
    }

    override fun saveRule(
        actor: Actor,
        id: UUID?,
        command: SaveRetentionRule,
    ): RetentionRule {
        requireAdmin(actor)
        val errors = mutableListOf<String>()
        if (command.name.isBlank()) errors += "Give the rule a name"
        if (command.ageDays !in 0..36_500) errors += "The age is 0 to 36500 days"
        if (command.action !in
            setOf("PURGE_ITERATIONS", "ARCHIVE")
        ) {
            errors += "The action is PURGE_ITERATIONS or ARCHIVE"
        }
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_RETENTION_RULE",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
        types.get(actor, TypeId(command.typeId))
        val existing =
            id?.let { i ->
                repository.rules(actor.tenant.value).firstOrNull { it.id == i }
                    ?: throw NotFoundException("RETENTION_RULE_NOT_FOUND", "No such rule")
            }
        val rule =
            RetentionRule(
                id ?: UuidV7.generate(clock),
                command.name.trim(),
                command.typeId,
                command.state
                    ?.trim()
                    ?.uppercase()
                    ?.ifEmpty { null },
                command.ageDays,
                command.action,
                command.active,
                existing?.createdAt ?: clock.instant(),
            )
        tx.executeWithoutResult { repository.saveRule(actor.tenant.value, rule, actor.principal.value) }
        return rule
    }

    override fun deleteRule(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        if (tx.execute { repository.deleteRule(actor.tenant.value, id) } ==
            0
        ) {
            throw NotFoundException("RETENTION_RULE_NOT_FOUND", "No such rule")
        }
    }

    override fun holds(actor: Actor): List<LegalHold> {
        requireAdmin(actor)
        return repository.holds(actor.tenant.value)
    }

    override fun createHold(
        actor: Actor,
        command: SaveLegalHold,
    ): LegalHold {
        requireAdmin(actor)
        if (command.name.isBlank()) throw ValidationException("INVALID_LEGAL_HOLD", "Give the hold a name")
        if (command.objectIds.isEmpty() &&
            command.contextIds.isEmpty()
        ) {
            throw ValidationException("INVALID_LEGAL_HOLD", "Choose what it holds")
        }
        command.objectIds.forEach { versioning.get(actor, MasterId(it)) }
        command.contextIds.forEach { contexts.get(actor, ContextId(it)) }
        val h =
            LegalHold(
                UuidV7.generate(clock),
                command.name.trim(),
                command.reason?.trim()?.ifEmpty {
                    null
                },
                command.objectIds,
                command.contextIds,
                clock.instant(),
                actor.principal.value,
                null,
                null,
            )
        tx.executeWithoutResult { repository.insertHold(actor.tenant.value, h) }
        return h
    }

    override fun releaseHold(
        actor: Actor,
        id: UUID,
    ): LegalHold {
        requireAdmin(actor)
        repository.hold(id) ?: throw NotFoundException("LEGAL_HOLD_NOT_FOUND", "No such legal hold")
        tx.executeWithoutResult { repository.release(id, clock.instant(), actor.principal.value) }
        return repository.hold(id)!!
    }

    /** Active holds on [obj]: on it, or on its context or any context above. */
    fun activeHolds(
        actor: Actor,
        obj: VersionedObject,
    ): List<LegalHold> {
        val system =
            Actor(
                actor.tenant,
                com.plm.platform.id
                    .PrincipalId("system"),
                siteAdmin = true,
            )
        val path = contexts.path(system, obj.contextId).map { it.id.value }
        return repository.activeHolds(actor.tenant.value, obj.id.value, path)
    }

    override fun holdsOn(
        actor: Actor,
        objectId: UUID,
    ): List<LegalHold> = activeHolds(actor, versioning.get(actor, MasterId(objectId)))

    /** Why [obj] can't be archived, or null. */
    private fun whyNotArchive(
        actor: Actor,
        obj: VersionedObject,
    ): String? {
        if (obj.checkedOutBy != null) return "checked out"
        val base = types.get(actor, obj.typeId).baseClass
        if (base == BaseClass.PART) {
            val users = bom.whereUsed(actor, obj.id).nodes
            if (users.isNotEmpty()) {
                return "still used by ${users.mapNotNull { it.parent.number }.distinct().take(
                    3,
                ).joinToString()}"
            }
        }
        if (base == BaseClass.DOCUMENT) {
            val parts = links.linkingParts(actor, obj.id)
            if (parts.isNotEmpty()) return "still describes ${parts.take(3).joinToString { it.part.number ?: "?" }}"
        }
        return null
    }

    /** What the rules would do now, object by object. */
    fun plan(actor: Actor): List<RetentionAction> {
        val rules = repository.rules(actor.tenant.value).filter { it.active }
        if (rules.isEmpty()) return emptyList()
        val now = clock.instant()
        val out = mutableListOf<RetentionAction>()
        var after: MasterId? = null
        val lineages = mutableMapOf<UUID, Set<UUID>>()
        do {
            val page = versioning.list(actor, ObjectQuery(limit = 500, after = after))
            for (o in page) {
                val lineage =
                    lineages.getOrPut(
                        o.typeId.value,
                    ) { types.lineage(actor, o.typeId).map { it.value }.toSet() }
                val age = Duration.between(o.latest.checkedInAt ?: o.latest.createdAt, now).toDays()
                val rule =
                    rules
                        .filter { r ->
                            r.typeId in lineage &&
                                (r.state == null || r.state == o.latest.state) &&
                                age >= r.ageDays
                        }
                        // Archiving goes further than purging: it wins.
                        .maxByOrNull { if (it.action == "ARCHIVE") 1 else 0 } ?: continue

                fun act(
                    action: String,
                    detail: String,
                ) = RetentionAction(o.id.value, o.number, o.name, o.latest.version, rule.name, action, detail)
                val holds = activeHolds(actor, o)
                if (holds.isNotEmpty()) {
                    out += act("SKIP", "on legal hold: ${holds.joinToString { it.name }}")
                    continue
                }
                if (rule.action == "ARCHIVE") {
                    val why = whyNotArchive(actor, o)
                    out +=
                        if (why ==
                            null
                        ) {
                            act("ARCHIVE", "${versioning.history(actor, o.id).size} iterations")
                        } else {
                            act("SKIP", why)
                        }
                } else {
                    val doomed = purge.purgeable(actor, o.id)
                    if (doomed.isNotEmpty()) {
                        out +=
                            act(
                                "PURGE_ITERATIONS",
                                doomed
                                    .sortedWith(
                                        compareBy({ it.revision }, { it.iteration }),
                                    ).joinToString { it.version },
                            )
                    }
                }
            }
            after = page.lastOrNull()?.id
        } while (page.size == 500)
        return out
    }

    override fun preview(actor: Actor): List<RetentionAction> {
        requireAdmin(actor)
        return tx.execute { plan(actor) }
    }

    override fun run(actor: Actor): Job {
        requireAdmin(actor)
        return jobs.submit(actor, SubmitJob(RetentionJob.TYPE, emptyMap()))
    }

    override fun archives(actor: Actor): List<Archive> {
        requireAdmin(actor)
        return repository.archives(actor.tenant.value)
    }

    override fun archive(
        actor: Actor,
        objectId: UUID,
    ): Archive {
        requireAdmin(actor)
        return tx.execute {
            val obj = versioning.get(actor, MasterId(objectId))
            if (activeHolds(
                    actor,
                    obj,
                ).isNotEmpty()
            ) {
                throw ConflictException("ON_LEGAL_HOLD", "${obj.number} is on legal hold")
            }
            whyNotArchive(actor, obj)?.let {
                throw ConflictException("ARCHIVE_NOT_ALLOWED", "${obj.number} can't be archived: $it")
            }
            val now = clock.instant()
            val written = archiver.write(actor, obj, now)
            val a =
                Archive(
                    UuidV7.generate(clock),
                    obj.id.value,
                    obj.number,
                    obj.name,
                    obj.contextId.value,
                    written.iterations,
                    written.files,
                    written.bytes.size.toLong(),
                    now,
                    actor.principal.value,
                    null,
                )
            repository.insertArchive(actor.tenant.value, a, obj.typeId.value, written.bytes)
            purge.purgeObject(actor, obj.id)
            a
        }
    }

    override fun restore(
        actor: Actor,
        archiveId: UUID,
    ): UUID {
        requireAdmin(actor)
        return tx.execute {
            val row =
                repository.archive(archiveId)?.takeIf { it.tenant == actor.tenant.value }
                    ?: throw NotFoundException("ARCHIVE_NOT_FOUND", "No such archive")
            if (row.view.restoredAt !=
                null
            ) {
                throw ConflictException("ALREADY_RESTORED", "${row.view.number} was already restored")
            }
            val id = archiver.restore(actor, repository.archivePackage(archiveId))
            repository.restored(archiveId, clock.instant(), actor.principal.value)
            id
        }
    }

    /** One object's action, in its own transaction; the report line. */
    fun apply(
        actor: Actor,
        a: RetentionAction,
    ): Map<String, Any?> =
        try {
            val message =
                when (a.action) {
                    "ARCHIVE" ->
                        archive(
                            actor,
                            a.objectId,
                        ).let { "Archived: ${it.iterations} iterations, ${it.files} files" }
                    "PURGE_ITERATIONS" ->
                        tx
                            .execute {
                                purge.purgeIterations(actor, MasterId(a.objectId))
                            }.let { "Purged $it iterations (${a.detail})" }
                    else -> "Skipped: ${a.detail}"
                }
            mapOf(
                "objectId" to a.objectId.toString(),
                "number" to a.number,
                "ok" to true,
                "action" to a.action,
                "message" to message,
            )
        } catch (e: PlmException) {
            mapOf(
                "objectId" to a.objectId.toString(),
                "number" to a.number,
                "ok" to false,
                "action" to a.action,
                "message" to (e.message ?: e.code),
            )
        }
}

/** Legal holds stop deletion and purging (roadmap 9.8). */
@Component
internal class LegalHoldGuard(
    private val retention: DefaultRetentionService,
    private val versioning: VersioningService,
) : OperationGuard {
    override fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal> {
        if (check.operation != GuardedOperation.DELETE) return emptyList()
        val id = check.objectId ?: return emptyList()
        val system =
            Actor(
                actor.tenant,
                com.plm.platform.id
                    .PrincipalId("system"),
                siteAdmin = true,
            )
        val holds = retention.activeHolds(actor, versioning.get(system, MasterId(id)))
        return holds.map { Refusal("legal-hold", "site", "On legal hold: ${it.name}") }
    }
}

/** Applies the retention rules (roadmap 9.8) and reports what it did to each object. */
@Component
internal class RetentionJob(
    private val retention: DefaultRetentionService,
    private val tx: TransactionTemplate,
) : JobHandler {
    override val type = TYPE
    override val label = "Retention"
    override val description = "Purges old iterations and archives objects as the retention rules say"
    override val adminOnly = true
    override val maxAttempts = 1

    override fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) = Unit

    override fun title(payload: Map<String, Any?>) = "Retention run"

    override fun run(context: JobContext): Map<String, Any?> {
        val actor = context.actor
        val plan = tx.execute { retention.plan(actor) }
        val results = mutableListOf<Map<String, Any?>>()
        plan.forEachIndexed { i, a ->
            context.checkCancelled()
            results += retention.apply(actor, a)
            context.progress(i + 1, plan.size, "${results.count { it["ok"] == true }} done")
        }
        return mapOf(
            "results" to results,
            "archived" to results.count { it["action"] == "ARCHIVE" && it["ok"] == true },
            "purged" to results.count { it["action"] == "PURGE_ITERATIONS" && it["ok"] == true },
            "skipped" to results.count { it["action"] == "SKIP" },
            "failed" to results.count { it["ok"] == false },
        )
    }

    companion object {
        const val TYPE = "retention.run"
    }
}
