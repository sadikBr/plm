package com.plm.versioning.domain

import com.plm.access.api.ContextService
import com.plm.lifecycle.api.LifecycleService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.types.api.AttributeValidator
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeService
import com.plm.versioning.api.HistoryImportService
import com.plm.versioning.api.ImportObjectHistory
import com.plm.versioning.api.ImportedHistory
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.IterationId
import com.plm.versioning.api.IterationStatus
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectCreated
import com.plm.versioning.infra.MasterRow
import com.plm.versioning.infra.OrganizingRepository
import com.plm.versioning.infra.VersioningRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

private val NUMBER = Regex("^[A-Za-z0-9][A-Za-z0-9._/ -]{0,99}$")
private val REVISION = Regex("^[A-Za-z0-9]{1,8}$")

@Service
@Transactional
internal class DefaultHistoryImportService(
    private val repository: VersioningRepository,
    private val folders: OrganizingRepository,
    private val types: TypeService,
    private val contexts: ContextService,
    private val lifecycles: LifecycleService,
    private val typeRules: TypeRuleService,
    private val validator: AttributeValidator,
    private val events: DomainEvents,
    private val clock: Clock,
) : HistoryImportService {
    private fun bad(message: String): Nothing = throw ValidationException("INVALID_HISTORY", message)

    override fun importObject(
        actor: Actor,
        command: ImportObjectHistory,
    ): ImportedHistory {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators bring in history")
        val name = command.name.trim()
        if (name.isEmpty() || name.length > 200) bad("A name must be 1-200 characters")
        if (!NUMBER.matches(command.number)) bad("'${command.number}' can't be used as a number")
        if (command.iterations.isEmpty()) bad("${command.number} has no iterations")
        val type = types.get(actor, command.typeId)
        if (!type.instantiable) {
            throw ValidationException(
                "TYPE_NOT_INSTANTIABLE",
                "${type.displayName} can't be created",
            )
        }
        val context = contexts.get(actor, command.contextId)
        if (!context.kind.holdsObjects) {
            throw ValidationException(
                "INVALID_CONTEXT",
                "${context.name} doesn't hold objects",
            )
        }
        val lifecycle = lifecycles.forType(actor, type.id)
        val states = lifecycle.states.map { it.key }.toSet()
        val seen = mutableSetOf<Pair<String, Int>>()
        for (i in command.iterations) {
            if (!REVISION.matches(i.revision)) bad("${command.number}: '${i.revision}' isn't a version label")
            if (i.iteration < 1) bad("${command.number} ${i.revision}.${i.iteration}: iterations count from 1")
            if (!seen.add(i.revision to i.iteration)) bad("${command.number} has ${i.revision}.${i.iteration} twice")
            if (i.state !in
                states
            ) {
                bad("${command.number} ${i.revision}.${i.iteration}: ${lifecycle.name} has no state ${i.state}")
            }
        }
        val masterId = MasterId(UuidV7.generate(clock))
        var previous: Iteration? = null
        val rows =
            command.iterations.map { past ->
                val attributes = validator.validate(actor, type.id, past.attributes, previous = null)
                Iteration(
                    id = IterationId(UuidV7.generate(clock)),
                    masterId = masterId,
                    revision = past.revision,
                    iteration = past.iteration,
                    status = IterationStatus.CHECKED_IN,
                    state = past.state,
                    attributes = attributes,
                    basedOnId = previous?.id,
                    comment = past.comment,
                    createdAt = past.createdAt.truncatedTo(ChronoUnit.MICROS),
                    createdBy = past.createdBy,
                    checkedInAt = past.createdAt.truncatedTo(ChronoUnit.MICROS),
                    checkedOutBy = null,
                ).also { previous = it }
            }
        val first = rows.first()
        val latest = rows.last()
        repository.insertMaster(
            actor.tenant,
            MasterRow(
                id = masterId,
                typeId = type.id,
                contextId = context.id,
                number = command.number,
                name = name,
                latestIterationId = latest.id,
                lifecycleId = lifecycle.id,
                revisionScheme = typeRules.rules(actor, type.id).revisionScheme,
                createdAt = first.createdAt,
                createdBy = first.createdBy,
                checkedOutBy = null,
            ),
        )
        rows.forEach { repository.insertIteration(actor.tenant, it) }
        command.folderId?.let { folder ->
            val f = folders.folder(actor.tenant, folder)
            if (f == null || f.contextId != context.id.value) bad("The folder isn't in ${context.name}")
            folders.move(masterId.value, context.id.value, folder)
        }
        events.publish(
            actor,
            ObjectCreated(
                masterId.value,
                command.number,
                type.id.value,
                context.id.value,
                latest.id.value,
                latest.version,
                name,
                latest.attributes,
            ),
        )
        return ImportedHistory(masterId, rows.map { it.id })
    }
}
