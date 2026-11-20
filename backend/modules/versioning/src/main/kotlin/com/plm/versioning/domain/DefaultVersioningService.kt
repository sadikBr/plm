package com.plm.versioning.domain

import com.plm.access.api.ContextService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeService
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.IterationId
import com.plm.versioning.api.IterationStatus
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import com.plm.versioning.infra.MasterRow
import com.plm.versioning.infra.VersioningRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val MAX_NAME_LENGTH = 200
private const val MAX_COMMENT_LENGTH = 2000
private val NUMBER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

/**
 * Every mutating operation starts by locking the master row (`SELECT ... FOR UPDATE`), so
 * operations on one object are serialized while different objects proceed in parallel.
 */
@Service
@Transactional
internal class DefaultVersioningService(
    private val repository: VersioningRepository,
    private val types: TypeService,
    private val contexts: ContextService,
    private val clock: Clock,
) : VersioningService {
    override fun create(
        actor: Actor,
        command: CreateVersionedObject,
    ): VersionedObject {
        val name = validName(command.name)
        command.number?.let(::validateNumber)
        validateAttributes(command.attributes)
        val type = types.get(actor, command.typeId)
        val context = contexts.get(actor, command.contextId)
        if (!context.kind.holdsObjects) {
            throw ValidationException(
                "INVALID_CONTEXT",
                "Objects must be created in a product, library or project, not in a ${context.kind}",
                mapOf("contextId" to context.id.value, "kind" to context.kind),
            )
        }

        val now = clock.instant()
        val masterId = MasterId(UuidV7.generate(clock))
        val first =
            Iteration(
                id = IterationId(UuidV7.generate(clock)),
                masterId = masterId,
                revision = RevisionSeries.first,
                iteration = 1,
                status = IterationStatus.CHECKED_IN,
                state = INITIAL_STATE,
                attributes = command.attributes,
                basedOnId = null,
                comment = null,
                createdAt = now,
                createdBy = actor.principal.value,
                checkedInAt = now,
                checkedOutBy = null,
            )
        val master =
            MasterRow(
                id = masterId,
                typeId = type.id,
                contextId = context.id,
                number = command.number ?: repository.nextNumber(),
                name = name,
                latestIterationId = first.id,
                createdAt = now,
                createdBy = actor.principal.value,
                checkedOutBy = null,
            )
        repository.insertMaster(actor.tenant, master)
        repository.insertIteration(actor.tenant, first)
        return master.toObject(first)
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: MasterId,
    ): VersionedObject {
        val master = findMaster(actor, id)
        return master.toObject(repository.iteration(master.latestIterationId))
    }

    @Transactional(readOnly = true)
    override fun history(
        actor: Actor,
        id: MasterId,
    ): List<Iteration> = repository.history(findMaster(actor, id).id)

    @Transactional(readOnly = true)
    override fun workingCopy(
        actor: Actor,
        id: MasterId,
    ): Iteration = ownWorkingCopy(actor, findMaster(actor, id))

    override fun checkOut(
        actor: Actor,
        id: MasterId,
    ): Iteration {
        val master = lockMaster(actor, id)
        repository.workingCopy(master.id)?.let { existing ->
            throw ConflictException(
                "ALREADY_CHECKED_OUT",
                "${master.number} is already checked out by ${existing.checkedOutBy}",
                mapOf("checkedOutBy" to existing.checkedOutBy),
            )
        }
        val latest = repository.iteration(master.latestIterationId)
        val workingCopy =
            latest.copy(
                id = IterationId(UuidV7.generate(clock)),
                iteration = latest.iteration + 1,
                status = IterationStatus.WORKING,
                basedOnId = latest.id,
                comment = null,
                createdAt = clock.instant(),
                createdBy = actor.principal.value,
                checkedInAt = null,
                checkedOutBy = actor.principal.value,
            )
        repository.insertIteration(actor.tenant, workingCopy)
        return workingCopy
    }

    override fun updateWorkingCopy(
        actor: Actor,
        id: MasterId,
        attributes: Map<String, Any?>,
    ): Iteration {
        validateAttributes(attributes)
        val workingCopy = ownWorkingCopy(actor, lockMaster(actor, id))
        repository.updateAttributes(workingCopy.id, attributes)
        return workingCopy.copy(attributes = attributes)
    }

    override fun checkIn(
        actor: Actor,
        id: MasterId,
        comment: String?,
    ): VersionedObject {
        val trimmed = comment?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null && trimmed.length > MAX_COMMENT_LENGTH) {
            throw ValidationException("INVALID_COMMENT", "Comment must be at most $MAX_COMMENT_LENGTH characters")
        }
        val master = lockMaster(actor, id)
        val workingCopy = ownWorkingCopy(actor, master)
        val checkedIn =
            workingCopy.copy(
                status = IterationStatus.CHECKED_IN,
                comment = trimmed,
                checkedInAt = clock.instant(),
                checkedOutBy = null,
            )
        repository.checkIn(checkedIn)
        repository.setLatest(master.id, checkedIn.id)
        return master.copy(latestIterationId = checkedIn.id, checkedOutBy = null).toObject(checkedIn)
    }

    override fun undoCheckOut(
        actor: Actor,
        id: MasterId,
    ): VersionedObject {
        val master = lockMaster(actor, id)
        repository.deleteWorkingCopy(ownWorkingCopy(actor, master).id)
        return master.copy(checkedOutBy = null).toObject(repository.iteration(master.latestIterationId))
    }

    override fun revise(
        actor: Actor,
        id: MasterId,
    ): VersionedObject {
        val master = lockMaster(actor, id)
        if (master.checkedOutBy != null) {
            throw ConflictException(
                "CHECKED_OUT",
                "${master.number} is checked out by ${master.checkedOutBy}; check it in or undo the check-out first",
                mapOf("checkedOutBy" to master.checkedOutBy),
            )
        }
        val latest = repository.iteration(master.latestIterationId)
        val now = clock.instant()
        val revised =
            latest.copy(
                id = IterationId(UuidV7.generate(clock)),
                revision = RevisionSeries.next(latest.revision),
                iteration = 1,
                state = INITIAL_STATE,
                basedOnId = latest.id,
                comment = null,
                createdAt = now,
                createdBy = actor.principal.value,
                checkedInAt = now,
            )
        repository.insertIteration(actor.tenant, revised)
        repository.setLatest(master.id, revised.id)
        return master.copy(latestIterationId = revised.id).toObject(revised)
    }

    private fun findMaster(
        actor: Actor,
        id: MasterId,
    ) = repository.findMaster(actor.tenant, id, forUpdate = false) ?: notFound(id)

    private fun lockMaster(
        actor: Actor,
        id: MasterId,
    ) = repository.findMaster(actor.tenant, id, forUpdate = true) ?: notFound(id)

    private fun notFound(id: MasterId): Nothing =
        throw NotFoundException("OBJECT_NOT_FOUND", "Object $id does not exist", mapOf("objectId" to id.value))

    private fun ownWorkingCopy(
        actor: Actor,
        master: MasterRow,
    ): Iteration {
        val workingCopy =
            repository.workingCopy(master.id)
                ?: throw ConflictException("NOT_CHECKED_OUT", "${master.number} is not checked out")
        if (workingCopy.checkedOutBy != actor.principal.value) {
            throw ForbiddenException(
                "CHECKED_OUT_BY_ANOTHER_USER",
                "${master.number} is checked out by ${workingCopy.checkedOutBy}",
                mapOf("checkedOutBy" to workingCopy.checkedOutBy),
            )
        }
        return workingCopy
    }

    private fun validName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) {
            throw ValidationException("INVALID_NAME", "Name must be 1-$MAX_NAME_LENGTH characters")
        }
        return trimmed
    }

    private fun validateNumber(number: String) {
        if (!NUMBER.matches(number)) {
            throw ValidationException(
                "INVALID_NUMBER",
                "Number must be 1-64 characters: letters, digits, '.', '_' or '-', starting with a letter or digit",
                mapOf("number" to number),
            )
        }
    }

    private fun validateAttributes(attributes: Map<String, Any?>) {
        val blank = attributes.keys.filter { it.isBlank() }
        if (blank.isNotEmpty()) {
            throw ValidationException("INVALID_ATTRIBUTES", "Attribute names must not be blank")
        }
    }

    private fun MasterRow.toObject(latest: Iteration) =
        VersionedObject(
            id = id,
            typeId = typeId,
            contextId = contextId,
            number = number,
            name = name,
            latest = latest,
            checkedOutBy = checkedOutBy,
            createdAt = createdAt,
            createdBy = createdBy,
        )

    private companion object {
        /** Until lifecycles exist (roadmap 1.x), every iteration starts In Work. */
        const val INITIAL_STATE = "IN_WORK"
    }
}
