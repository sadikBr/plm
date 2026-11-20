package com.plm.versioning.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextService
import com.plm.access.api.Permission
import com.plm.identity.api.PrincipalDirectory
import com.plm.lifecycle.api.LifecycleService
import com.plm.lifecycle.api.LifecycleTemplate
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.expression.ExpressionException
import com.plm.platform.expression.Expressions
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.UuidV7
import com.plm.preferences.api.PreferenceService
import com.plm.types.api.AttributeValidator
import com.plm.types.api.NumberingSchemeId
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeService
import com.plm.types.api.ValidationRuleService
import com.plm.versioning.api.ChangeLifecycle
import com.plm.versioning.api.CheckOutUndone
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.IterationId
import com.plm.versioning.api.IterationStatus
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectCheckedIn
import com.plm.versioning.api.ObjectCheckedOut
import com.plm.versioning.api.ObjectCreated
import com.plm.versioning.api.ObjectLifecycle
import com.plm.versioning.api.ObjectLifecycleChanged
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.ObjectRenamed
import com.plm.versioning.api.ObjectRevised
import com.plm.versioning.api.ObjectStateChanged
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.Rename
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import com.plm.versioning.api.WorkingCopyUpdated
import com.plm.versioning.infra.MasterRow
import com.plm.versioning.infra.VersioningRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val MAX_NAME_LENGTH = 200
private const val MAX_COMMENT_LENGTH = 2000
private const val MAX_PAGE_SIZE = 500
private const val MAX_QUERY_LENGTH = 100
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
    private val access: AccessService,
    private val events: DomainEvents,
    private val directory: PrincipalDirectory,
    private val attributeValidator: AttributeValidator,
    private val typeRules: TypeRuleService,
    private val lifecycles: LifecycleService,
    private val preferences: PreferenceService,
    private val models: ObjectModels,
    private val validationRules: ValidationRuleService,
    private val calculations: Calculations,
    private val guards: ObjectProvider<OperationGuard>,
    private val clock: Clock,
) : VersioningService {
    override fun create(
        actor: Actor,
        command: CreateVersionedObject,
    ): VersionedObject {
        val name = validName(command.name)
        command.number?.let(::validateNumber)
        val type = types.get(actor, command.typeId)
        if (!type.instantiable) {
            throw ValidationException(
                "TYPE_NOT_INSTANTIABLE",
                "${type.displayName} only groups other types: create one of its subtypes",
                mapOf("typeId" to type.id.value),
            )
        }
        val context = contexts.get(actor, command.contextId)
        if (!context.kind.holdsObjects) {
            throw ValidationException(
                "INVALID_CONTEXT",
                "Objects must be created in a product, library or project, not in a ${context.kind}",
                mapOf("contextId" to context.id.value, "kind" to context.kind),
            )
        }
        val rules = typeRules.rules(actor, type.id)
        if (command.number != null && !rules.allowManualNumber) {
            throw ValidationException(
                "MANUAL_NUMBER_NOT_ALLOWED",
                "${type.displayName} numbers are assigned automatically; leave the number empty",
            )
        }
        val lifecycle = lifecycles.forType(actor, type.id)
        access.require(actor, AccessTarget(context.id, type.id, lifecycle.initialState), Permission.CREATE)
        val entered =
            attributeValidator.validate(
                actor,
                type.id,
                calculations.userValues(actor, type.id, command.attributes),
                previous = null,
            )
        val number =
            command.number
                ?: rules.numberingSchemeId?.let { typeRules.nextNumber(actor, it) }
                ?: contextScheme(actor, context.id)?.let { typeRules.nextNumber(actor, it) }
                ?: repository.nextNumber()

        val facts =
            ObjectFacts(
                typeId = type.id,
                contextId = context.id,
                number = number,
                name = name,
                state = lifecycle.initialState,
                revision = rules.revisionScheme.first,
                iteration = 1,
                checkedOut = false,
                attributes = entered,
            )
        val attributes = calculations.apply(actor, facts, previous = null)
        enforceRules(actor, facts.copy(attributes = attributes))
        guard(actor, GuardedOperation.CREATE, facts.copy(attributes = attributes))
        val now = clock.instant()
        val masterId = MasterId(UuidV7.generate(clock))
        val first =
            Iteration(
                id = IterationId(UuidV7.generate(clock)),
                masterId = masterId,
                revision = rules.revisionScheme.first,
                iteration = 1,
                status = IterationStatus.CHECKED_IN,
                state = lifecycle.initialState,
                attributes = attributes,
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
                number = number,
                name = name,
                latestIterationId = first.id,
                lifecycleId = lifecycle.id,
                revisionScheme = rules.revisionScheme,
                createdAt = now,
                createdBy = actor.principal.value,
                checkedOutBy = null,
            )
        repository.insertMaster(actor.tenant, master)
        repository.insertIteration(actor.tenant, first)
        events.publish(
            actor,
            ObjectCreated(
                master.id.value,
                master.number,
                master.typeId.value,
                master.contextId.value,
                first.id.value,
                first.version,
                master.name,
                first.attributes,
            ),
        )
        return master.toObject(first)
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        id: MasterId,
    ): VersionedObject {
        val master = findMaster(actor, id, forUpdate = false)
        val latest = readable(actor, master)
        return master.toObject(latest)
    }

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        query: ObjectQuery,
    ): List<VersionedObject> {
        // An explicit context must be visible (404 otherwise); objects are then filtered per row in SQL.
        val context = query.contextId?.let { contexts.get(actor, it).id }
        val text = query.text?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > MAX_QUERY_LENGTH) {
            throw ValidationException("INVALID_QUERY", "Search text must be at most $MAX_QUERY_LENGTH characters")
        }
        val filter = access.filter(actor, Permission.READ, AccessColumns("m.context_id", "m.type_id", "i.state"))
        return repository
            .list(actor.tenant, context, text, filter, query.limit.coerceIn(1, MAX_PAGE_SIZE), query.after)
            .map { (master, latest) -> master.toObject(latest) }
    }

    @Transactional(readOnly = true)
    override fun history(
        actor: Actor,
        id: MasterId,
    ): List<Iteration> {
        val master = findMaster(actor, id, forUpdate = false)
        readable(actor, master)
        return repository.history(master.id)
    }

    @Transactional(readOnly = true)
    override fun workingCopy(
        actor: Actor,
        id: MasterId,
    ): Iteration {
        val master = findMaster(actor, id, forUpdate = false)
        readable(actor, master)
        return ownWorkingCopy(actor, master)
    }

    override fun checkOut(
        actor: Actor,
        id: MasterId,
    ): Iteration {
        val master = findMaster(actor, id, forUpdate = true)
        val latest = authorize(actor, master, Permission.MODIFY)
        val lifecycle = lifecycleOf(actor, master)
        if (!lifecycle.isEditable(latest.state)) {
            throw ConflictException(
                "NOT_EDITABLE",
                "${master.number} is ${stateName(lifecycle, latest.state)} and can't be changed. " +
                    "Revise it to start a new revision you can edit.",
                mapOf("state" to latest.state),
            )
        }
        guard(actor, GuardedOperation.CHECK_OUT, master.facts(latest))
        repository.workingCopy(master.id)?.let { existing ->
            throw ConflictException(
                "ALREADY_CHECKED_OUT",
                "${master.number} is already checked out by ${nameOf(actor, existing.checkedOutBy)}",
                mapOf("checkedOutBy" to existing.checkedOutBy),
            )
        }
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
        events.publish(actor, master.event(workingCopy, ::ObjectCheckedOut))
        return workingCopy
    }

    override fun updateWorkingCopy(
        actor: Actor,
        id: MasterId,
        attributes: Map<String, Any?>,
    ): Iteration {
        val master = findMaster(actor, id, forUpdate = true)
        authorize(actor, master, Permission.MODIFY)
        val workingCopy = ownWorkingCopy(actor, master)
        val entered =
            attributeValidator.validate(
                actor,
                master.typeId,
                calculations.userValues(actor, master.typeId, attributes),
                basedOn(workingCopy),
            )
        val normalized = calculations.apply(actor, master.facts(workingCopy, entered), basedOn(workingCopy))
        repository.updateAttributes(workingCopy.id, normalized)
        val updated = workingCopy.copy(attributes = normalized)
        events.publish(
            actor,
            WorkingCopyUpdated(
                master.id.value,
                master.number,
                master.typeId.value,
                master.contextId.value,
                updated.id.value,
                updated.version,
                workingCopy.attributes,
                normalized,
            ),
        )
        return updated
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
        val master = findMaster(actor, id, forUpdate = true)
        authorize(actor, master, Permission.MODIFY)
        val workingCopy = ownWorkingCopy(actor, master)
        if (trimmed == null && preferences.value(actor, master.contextId, CHECK_IN_COMMENT_REQUIRED) == true) {
            throw ValidationException(
                "CHECK_IN_COMMENT_REQUIRED",
                "Say what changed: a check-in comment is required here",
            )
        }
        // Re-validate: definitions may have changed since check-out (e.g. a newly required attribute).
        val validated = attributeValidator.validate(actor, master.typeId, workingCopy.attributes, basedOn(workingCopy))
        val attributes =
            calculations.apply(
                actor,
                master.facts(workingCopy, validated).copy(checkedOut = false),
                basedOn(workingCopy),
            )
        enforceRules(
            actor,
            master.facts(workingCopy, attributes).copy(checkedOut = false),
        )
        guard(
            actor,
            GuardedOperation.CHECK_IN,
            master.facts(workingCopy, attributes).copy(checkedOut = false),
            mapOf("comment" to trimmed),
        )
        if (attributes != workingCopy.attributes) repository.updateAttributes(workingCopy.id, attributes)
        val checkedIn =
            workingCopy.copy(
                attributes = attributes,
                status = IterationStatus.CHECKED_IN,
                comment = trimmed,
                checkedInAt = clock.instant(),
                checkedOutBy = null,
            )
        repository.checkIn(checkedIn)
        repository.setLatest(master.id, checkedIn.id)
        events.publish(
            actor,
            ObjectCheckedIn(
                master.id.value,
                master.number,
                master.typeId.value,
                master.contextId.value,
                checkedIn.id.value,
                checkedIn.version,
                checkedIn.comment,
            ),
        )
        return master.copy(latestIterationId = checkedIn.id, checkedOutBy = null).toObject(checkedIn)
    }

    override fun undoCheckOut(
        actor: Actor,
        id: MasterId,
    ): VersionedObject {
        val master = findMaster(actor, id, forUpdate = true)
        val latest = authorize(actor, master, Permission.MODIFY)
        val workingCopy = ownWorkingCopy(actor, master)
        repository.deleteWorkingCopy(workingCopy.id)
        events.publish(actor, master.event(workingCopy, ::CheckOutUndone))
        return master.copy(checkedOutBy = null).toObject(latest)
    }

    override fun revise(
        actor: Actor,
        id: MasterId,
    ): VersionedObject {
        val master = findMaster(actor, id, forUpdate = true)
        val latest = authorize(actor, master, Permission.REVISE)
        if (master.checkedOutBy != null) {
            throw ConflictException(
                "CHECKED_OUT",
                "${master.number} is checked out by ${nameOf(actor, master.checkedOutBy)}; " +
                    "check it in or undo the check-out first",
                mapOf("checkedOutBy" to master.checkedOutBy),
            )
        }
        val lifecycle = lifecycleOf(actor, master)
        if (!lifecycle.isRevisable(latest.state)) {
            val from = lifecycle.states.filter { it.revisable }.joinToString(" or ") { it.labels["en"] ?: it.key }
            throw ConflictException(
                "REVISE_NOT_ALLOWED",
                "${master.number} is ${stateName(
                    lifecycle,
                    latest.state,
                )}; only ${from.ifEmpty { "no" }} objects can be revised",
                mapOf("state" to latest.state),
            )
        }
        guard(actor, GuardedOperation.REVISE, master.facts(latest))
        // State-based series: the state revised from may switch the series (e.g. 1, 2, 3 then A, B).
        val series = lifecycle.state(latest.state)?.revisionScheme ?: master.revisionScheme
        val nextRevision =
            if (series == master.revisionScheme && series.isValid(latest.revision)) {
                series.next(latest.revision)
            } else {
                series.first
            }
        if (series != master.revisionScheme) repository.setRevisionScheme(master.id, series)
        val now = clock.instant()
        val revised =
            latest.copy(
                id = IterationId(UuidV7.generate(clock)),
                revision = nextRevision,
                iteration = 1,
                state = lifecycle.initialState,
                basedOnId = latest.id,
                comment = null,
                createdAt = now,
                createdBy = actor.principal.value,
                checkedInAt = now,
            )
        repository.insertIteration(actor.tenant, revised)
        repository.setLatest(master.id, revised.id)
        events.publish(
            actor,
            ObjectRevised(
                master.id.value,
                master.number,
                master.typeId.value,
                master.contextId.value,
                revised.id.value,
                revised.version,
                latest.version,
            ),
        )
        return master.copy(latestIterationId = revised.id).toObject(revised)
    }

    override fun setState(
        actor: Actor,
        id: MasterId,
        command: SetState,
    ): VersionedObject {
        val comment = command.comment?.trim()?.takeIf { it.isNotEmpty() }
        if (comment != null && comment.length > MAX_COMMENT_LENGTH) {
            throw ValidationException("INVALID_COMMENT", "Comment must be at most $MAX_COMMENT_LENGTH characters")
        }
        val master = findMaster(actor, id, forUpdate = true)
        val latest =
            if (command.override) {
                if (!actor.siteAdmin) {
                    throw ForbiddenException(
                        "ADMIN_REQUIRED",
                        "Only site administrators can override the lifecycle",
                    )
                }
                readable(actor, master)
            } else {
                authorize(actor, master, Permission.PROMOTE)
            }
        if (master.checkedOutBy != null) {
            throw ConflictException(
                "CHECKED_OUT",
                "${master.number} is checked out by ${nameOf(actor, master.checkedOutBy)}; check it in first",
                mapOf("checkedOutBy" to master.checkedOutBy),
            )
        }
        val lifecycle = lifecycleOf(actor, master)
        val target =
            lifecycle.state(command.state)
                ?: throw ValidationException(
                    "UNKNOWN_STATE",
                    "'${command.state}' is not a state of the ${lifecycle.name} lifecycle",
                    mapOf("states" to lifecycle.states.map { it.key }),
                )
        if (target.key == latest.state) {
            throw ValidationException("SAME_STATE", "${master.number} is already ${stateName(lifecycle, latest.state)}")
        }
        // Entry criteria: the state's required attributes must have values (an admin override skips them).
        val missing = target.requiredAttributes.filter { isBlank(latest.attributes[it]) }
        if (!command.override && missing.isNotEmpty()) {
            throw ValidationException(
                "ENTRY_CRITERIA_NOT_MET",
                "${master.number} can't become ${stateName(
                    lifecycle,
                    target.key,
                )} yet: fill in ${missing.joinToString()}",
                mapOf("missing" to missing, "state" to target.key),
            )
        }
        // Gate: the state's entry condition, on the object as it is now (an admin override skips it).
        val gate = target.entryCondition
        if (!command.override && gate != null) {
            val met =
                try {
                    Expressions.test(gate, models.build(actor, master.facts(latest)))
                } catch (e: ExpressionException) {
                    throw ValidationException(
                        "ENTRY_CRITERIA_NOT_MET",
                        "${target.entryMessage ?: "The entry condition isn't met"} (it couldn't be checked: ${e.message})",
                        mapOf("state" to target.key),
                    )
                }
            if (!met) {
                throw ValidationException(
                    "ENTRY_CRITERIA_NOT_MET",
                    target.entryMessage
                        ?: "${master.number} doesn't meet the conditions to become ${stateName(lifecycle, target.key)}",
                    mapOf("state" to target.key, "condition" to gate),
                )
            }
        }
        if (!command.override && !lifecycle.canTransition(latest.state, target.key)) {
            throw ConflictException(
                "TRANSITION_NOT_ALLOWED",
                "${master.number} can't go from ${stateName(
                    lifecycle,
                    latest.state,
                )} to ${stateName(lifecycle, target.key)}",
                mapOf(
                    "from" to latest.state,
                    "to" to target.key,
                    "allowed" to lifecycle.nextStates(latest.state).map { it.key },
                ),
            )
        }
        if (!command.override) {
            guard(
                actor,
                GuardedOperation.SET_STATE,
                master.facts(latest),
                mapOf("from" to latest.state, "to" to target.key, "comment" to comment),
            )
        }
        repository.setState(latest.id, target.key)
        events.publish(
            actor,
            ObjectStateChanged(
                master.id.value,
                master.number,
                master.typeId.value,
                master.contextId.value,
                latest.id.value,
                latest.version,
                latest.state,
                target.key,
                comment,
                command.override,
            ),
        )
        return master.toObject(latest.copy(state = target.key))
    }

    override fun rename(
        actor: Actor,
        id: MasterId,
        command: Rename,
    ): VersionedObject {
        val comment = command.comment?.trim()?.takeIf { it.isNotEmpty() }
        if (comment != null && comment.length > MAX_COMMENT_LENGTH) {
            throw ValidationException("INVALID_COMMENT", "Comment must be at most $MAX_COMMENT_LENGTH characters")
        }
        val master = findMaster(actor, id, forUpdate = true)
        // Renumbering changes what every document, drawing and system refers to: a manager's call.
        val latest = authorize(actor, master, Permission.MANAGE)
        if (master.checkedOutBy != null) {
            throw ConflictException(
                "CHECKED_OUT",
                "${master.number} is checked out by ${nameOf(actor, master.checkedOutBy)}; check it in first",
                mapOf("checkedOutBy" to master.checkedOutBy),
            )
        }
        val number = command.number?.trim()?.also(::validateNumber) ?: master.number
        val name = command.name?.let(::validName) ?: master.name
        if (number == master.number && name == master.name) {
            throw ValidationException("NO_CHANGE", "${master.number} already has this number and name")
        }
        guard(actor, GuardedOperation.RENAME, master.facts(latest), mapOf("number" to number, "name" to name))
        repository.rename(master.id, number, name)
        val renamed = master.copy(number = number, name = name)
        events.publish(
            actor,
            renamed.event(latest) { objectId, n, typeId, contextId, iterationId, version ->
                ObjectRenamed(
                    objectId,
                    n,
                    typeId,
                    contextId,
                    iterationId,
                    version,
                    master.number,
                    master.name,
                    name,
                    comment,
                )
            },
        )
        return renamed.toObject(latest)
    }

    @Transactional(readOnly = true)
    override fun expressionModel(
        actor: Actor,
        id: MasterId,
    ): Map<String, Any?> {
        val master = findMaster(actor, id, forUpdate = false)
        return models.build(actor, master.facts(readable(actor, master)))
    }

    /**
     * The type's validation rules (and its ancestors'), on the object as it would be stored. All
     * failures are reported together, keyed by the attribute each is about (or the rule's name),
     * like attribute errors, so forms show them next to their fields. A rule that can't be
     * evaluated counts as failed: rules fail closed.
     */
    private fun enforceRules(
        actor: Actor,
        facts: ObjectFacts,
    ) {
        val active = validationRules.rules(actor, facts.typeId).filter { it.active }
        if (active.isEmpty()) return
        val model = models.build(actor, facts)
        val failed =
            active.mapNotNull { rule ->
                try {
                    if (Expressions.test(rule.condition, model)) null else rule to rule.message
                } catch (e: ExpressionException) {
                    rule to "${rule.message} (the rule “${rule.name}” couldn't be checked: ${e.message})"
                }
            }
        if (failed.isEmpty()) return
        throw ValidationException(
            "INVALID_ATTRIBUTES",
            failed.joinToString(" · ") { it.second },
            mapOf(
                "errors" to
                    failed.map { (rule, message) ->
                        mapOf(
                            "attribute" to (rule.attribute ?: rule.name),
                            "code" to "RULE_FAILED",
                            "message" to message,
                            "rule" to rule.name,
                        )
                    },
            ),
        )
    }

    /**
     * Asks every [OperationGuard] (the extensions module's rules, roadmap 2.5) whether [operation]
     * may happen, and refuses it with all their reasons (`OPERATION_REFUSED`). The object model is
     * built only if a guard needs it.
     */
    private fun guard(
        actor: Actor,
        operation: GuardedOperation,
        facts: ObjectFacts,
        event: Map<String, Any?> = emptyMap(),
    ) {
        val all = guards.orderedStream().toList()
        if (all.isEmpty()) return
        val check =
            OperationCheck(
                operation,
                facts.typeId,
                mapOf("operation" to operation.key) + event,
            ) { models.build(actor, facts) }
        val refusals = all.flatMap { it.check(actor, check) }
        if (refusals.isEmpty()) return
        throw ValidationException(
            "OPERATION_REFUSED",
            refusals.joinToString(" · ") { it.message },
            mapOf(
                "operation" to operation.key,
                "refusals" to refusals.map { mapOf("rule" to it.rule, "source" to it.source, "message" to it.message) },
            ),
        )
    }

    private fun MasterRow.facts(
        iteration: Iteration,
        attributes: Map<String, Any?> = iteration.attributes,
        state: String = iteration.state,
    ) = ObjectFacts(
        typeId = typeId,
        contextId = contextId,
        number = number,
        name = name,
        state = state,
        revision = iteration.revision,
        iteration = iteration.iteration,
        checkedOut = checkedOutBy != null,
        attributes = attributes,
    )

    @Transactional(readOnly = true)
    override fun lifecycle(
        actor: Actor,
        id: MasterId,
    ): ObjectLifecycle {
        val master = findMaster(actor, id, forUpdate = false)
        val latest = readable(actor, master)
        val lifecycle = lifecycleOf(actor, master)
        val canPromote = access.has(actor, master.target(latest), Permission.PROMOTE)
        return ObjectLifecycle(
            template = lifecycle,
            state = latest.state,
            editable = lifecycle.isEditable(latest.state),
            revisable = lifecycle.isRevisable(latest.state),
            revisionScheme = master.revisionScheme,
            nextStates = if (canPromote) lifecycle.nextStates(latest.state).map { it.key } else emptyList(),
            canOverride = actor.siteAdmin,
        )
    }

    private fun isBlank(value: Any?) =
        value == null || (value is String && value.isBlank()) || (value is Collection<*> && value.isEmpty())

    override fun changeLifecycle(
        actor: Actor,
        id: MasterId,
        command: ChangeLifecycle,
    ): VersionedObject {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators can move objects to another lifecycle",
            )
        }
        val master = findMaster(actor, id, forUpdate = true)
        val latest = readable(actor, master)
        if (master.checkedOutBy != null) {
            throw ConflictException(
                "CHECKED_OUT",
                "${master.number} is checked out by ${nameOf(actor, master.checkedOutBy)}; check it in first",
                mapOf("checkedOutBy" to master.checkedOutBy),
            )
        }
        val from = lifecycleOf(actor, master)
        val to = lifecycles.get(actor, command.lifecycleId)
        // Keep the state when the new lifecycle has it; otherwise the caller says where it goes.
        val state =
            command.state ?: latest.state.takeIf { to.state(it) != null }
                ?: throw ValidationException(
                    "STATE_MAPPING_REQUIRED",
                    "${to.name} v${to.version} has no state ${stateName(
                        from,
                        latest.state,
                    )}: choose the state ${master.number} should be in",
                    mapOf("states" to to.states.map { it.key }),
                )
        if (to.state(state) == null) {
            throw ValidationException(
                "UNKNOWN_STATE",
                "'$state' is not a state of ${to.name}",
                mapOf(
                    "states" to to.states.map { it.key },
                ),
            )
        }
        if (to.id == from.id &&
            state == latest.state
        ) {
            throw ValidationException("NO_CHANGE", "${master.number} already uses this lifecycle")
        }
        guard(
            actor,
            GuardedOperation.CHANGE_LIFECYCLE,
            master.facts(latest),
            mapOf(
                "lifecycle" to to.name,
                "to" to state,
            ),
        )
        repository.setLifecycle(master.id, to.id)
        if (state != latest.state) repository.setState(latest.id, state)
        events.publish(
            actor,
            master.event(latest) { objectId, n, typeId, contextId, iterationId, version ->
                ObjectLifecycleChanged(
                    objectId,
                    n,
                    typeId,
                    contextId,
                    iterationId,
                    version,
                    from.id.value,
                    to.id.value,
                    "${to.name} v${to.version}",
                    latest.state,
                    state,
                )
            },
        )
        return master.copy(lifecycleId = to.id).toObject(latest.copy(state = state))
    }

    /** The numbering scheme the context's preferences choose for types without their own, if any. */
    private fun contextScheme(
        actor: Actor,
        context: ContextId,
    ): NumberingSchemeId? {
        val name = preferences.value(actor, context, DEFAULT_NUMBERING_SCHEME) as? String ?: return null
        return typeRules.numberingSchemes(actor).find { it.name == name }?.id
    }

    private fun lifecycleOf(
        actor: Actor,
        master: MasterRow,
    ): LifecycleTemplate = lifecycles.get(actor, master.lifecycleId)

    private fun stateName(
        lifecycle: LifecycleTemplate,
        state: String,
    ) = lifecycle.state(state)?.labels?.get("en") ?: state

    private fun findMaster(
        actor: Actor,
        id: MasterId,
        forUpdate: Boolean,
    ) = repository.findMaster(actor.tenant, id, forUpdate) ?: notFound(id)

    /** Returns the latest iteration if the actor may read the object; otherwise pretends it doesn't exist. */
    private fun readable(
        actor: Actor,
        master: MasterRow,
    ): Iteration {
        val latest = repository.iteration(master.latestIterationId)
        if (!access.has(actor, master.target(latest), Permission.READ)) notFound(master.id)
        return latest
    }

    /** READ (else not found), then [permission] (else access denied). Returns the latest iteration. */
    private fun authorize(
        actor: Actor,
        master: MasterRow,
        permission: Permission,
    ): Iteration {
        val latest = readable(actor, master)
        access.require(actor, master.target(latest), permission)
        return latest
    }

    private fun MasterRow.target(latest: Iteration) = AccessTarget(contextId, typeId, latest.state)

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
                "${master.number} is checked out by ${nameOf(actor, workingCopy.checkedOutBy)}",
                mapOf("checkedOutBy" to workingCopy.checkedOutBy),
            )
        }
        return workingCopy
    }

    private fun nameOf(
        actor: Actor,
        principal: String?,
    ) = principal?.let { directory.displayName(actor.tenant, PrincipalId(it)) } ?: "someone"

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

    /** Values of the checked-in iteration a working copy was made from: the baseline for immutable attributes. */
    private fun basedOn(workingCopy: Iteration): Map<String, Any?> =
        workingCopy.basedOnId?.let { repository.iteration(it).attributes } ?: emptyMap()

    private fun <E> MasterRow.event(
        iteration: Iteration,
        factory: (java.util.UUID, String, java.util.UUID, java.util.UUID, java.util.UUID, String) -> E,
    ): E = factory(id.value, number, typeId.value, contextId.value, iteration.id.value, iteration.version)

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
}
