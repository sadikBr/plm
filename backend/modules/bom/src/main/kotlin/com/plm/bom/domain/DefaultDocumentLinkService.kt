package com.plm.bom.domain

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.Permission
import com.plm.bom.api.DocumentLink
import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.DocumentLinkRule
import com.plm.bom.api.DocumentLinkService
import com.plm.bom.api.DocumentLinksChanged
import com.plm.bom.api.LinkingPart
import com.plm.bom.infra.DocumentLinkRepository
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultDocumentLinkService(
    private val repository: DocumentLinkRepository,
    private val versioning: VersioningService,
    private val access: AccessService,
    private val types: TypeService,
    private val events: DomainEvents,
    private val clock: Clock,
) : DocumentLinkService {
    private fun readable(actor: Actor) =
        access.filter(actor, Permission.READ, AccessColumns("m.context_id", "m.type_id", "li.state", "m.id"))

    @Transactional(readOnly = true)
    override fun links(
        actor: Actor,
        partId: MasterId,
        iterationId: UUID?,
        workingCopy: Boolean,
    ): List<DocumentLink> {
        val part = versioning.get(actor, partId)
        val iteration =
            when {
                workingCopy -> versioning.workingCopy(actor, partId).id.value
                iterationId != null ->
                    versioning
                        .history(actor, partId)
                        .firstOrNull { it.id.value == iterationId }
                        ?.id
                        ?.value
                        ?: throw NotFoundException("ITERATION_NOT_FOUND", "There's no such iteration of ${part.number}")
                else -> part.latest.id.value
            }
        return repository.links(iteration, readable(actor))
    }

    @Transactional(readOnly = true)
    override fun linkingParts(
        actor: Actor,
        documentId: MasterId,
    ): List<LinkingPart> {
        val document = versioning.get(actor, documentId)
        return repository.linkingParts(document.id.value, readable(actor))
    }

    private fun baseClass(
        actor: Actor,
        typeId: TypeId,
    ) = types.get(actor, typeId).baseClass

    /** No rule for the part type or its ancestors: anything goes; otherwise one must match. */
    private fun requireAllowed(
        actor: Actor,
        partType: TypeId,
        documentType: TypeId,
        kind: DocumentLinkKind,
    ) {
        val partLineage = types.lineage(actor, partType).map { it.value }.toSet()
        val rules = repository.rules(actor.tenant).filter { it.partTypeId in partLineage }
        if (rules.isEmpty()) return
        val documentLineage = types.lineage(actor, documentType).map { it.value }.toSet()
        if (rules.none { it.kind == kind && it.documentTypeId in documentLineage }) {
            throw ValidationException(
                "LINK_NOT_ALLOWED",
                "A ${types.get(
                    actor,
                    partType,
                ).displayName} can't be linked this way to a ${types.get(actor, documentType).displayName}",
            )
        }
    }

    override fun link(
        actor: Actor,
        partId: MasterId,
        kind: DocumentLinkKind,
        documentId: UUID,
    ): DocumentLink {
        val part = versioning.get(actor, partId)
        val wc = versioning.workingCopy(actor, partId).id.value
        if (baseClass(actor, part.typeId) !=
            BaseClass.PART
        ) {
            throw ValidationException("NOT_A_PART", "Only parts link to documents")
        }
        val document = versioning.get(actor, MasterId(documentId))
        if (baseClass(actor, document.typeId) != BaseClass.DOCUMENT) {
            throw ValidationException("NOT_A_DOCUMENT", "${document.number} isn't a document")
        }
        requireAllowed(actor, part.typeId, document.typeId, kind)
        if (repository.exists(
                wc,
                kind,
                document.id.value,
            )
        ) {
            throw ConflictException("DUPLICATE_LINK", "${document.number} is already linked this way")
        }
        val id = UuidV7.generate(clock)
        val documentIteration = if (kind == DocumentLinkKind.DESCRIBED_BY) document.latest.id.value else null
        repository.insert(
            actor.tenant,
            id,
            wc,
            kind,
            document.id.value,
            documentIteration,
            clock.instant(),
            actor.principal.value,
        )
        events.publish(
            actor,
            DocumentLinksChanged(part.id.value, wc, "LINKED", kind, document.id.value, document.number),
        )
        return repository.links(wc, readable(actor)).first { it.id == id }
    }

    private fun own(
        actor: Actor,
        partId: MasterId,
        linkId: UUID,
    ): Pair<UUID, com.plm.bom.infra.LinkRecord> {
        val wc = versioning.workingCopy(actor, partId).id.value
        val record = repository.record(linkId)
        if (record == null ||
            record.partIterationId != wc
        ) {
            throw NotFoundException("LINK_NOT_FOUND", "There's no such link in your working copy")
        }
        return wc to record
    }

    override fun updateToLatest(
        actor: Actor,
        partId: MasterId,
        linkId: UUID,
    ): DocumentLink {
        val (wc, record) = own(actor, partId, linkId)
        if (record.kind !=
            DocumentLinkKind.DESCRIBED_BY
        ) {
            throw ValidationException("INVALID_LINK", "References always show the latest")
        }
        val document = versioning.get(actor, MasterId(record.documentMasterId))
        repository.pointTo(linkId, document.latest.id.value)
        events.publish(
            actor,
            DocumentLinksChanged(partId.value, wc, "UPDATED", record.kind, document.id.value, document.number),
        )
        return repository.links(wc, readable(actor)).first { it.id == linkId }
    }

    override fun unlink(
        actor: Actor,
        partId: MasterId,
        linkId: UUID,
    ) {
        val (wc, record) = own(actor, partId, linkId)
        repository.delete(linkId)
        events.publish(
            actor,
            DocumentLinksChanged(partId.value, wc, "UNLINKED", record.kind, record.documentMasterId, null),
        )
    }

    @Transactional(readOnly = true)
    override fun rules(actor: Actor): List<DocumentLinkRule> = repository.rules(actor.tenant)

    override fun addRule(
        actor: Actor,
        partTypeId: UUID,
        documentTypeId: UUID,
        kind: DocumentLinkKind,
    ): DocumentLinkRule {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators set link rules")
        if (baseClass(actor, TypeId(partTypeId)) !=
            BaseClass.PART
        ) {
            throw ValidationException("NOT_A_PART", "Choose a part type")
        }
        if (baseClass(actor, TypeId(documentTypeId)) !=
            BaseClass.DOCUMENT
        ) {
            throw ValidationException("NOT_A_DOCUMENT", "Choose a document type")
        }
        val rule = DocumentLinkRule(UuidV7.generate(clock), partTypeId, documentTypeId, kind)
        repository.addRule(actor.tenant, rule)
        return repository.rules(actor.tenant).first {
            it.partTypeId == partTypeId &&
                it.documentTypeId == documentTypeId &&
                it.kind == kind
        }
    }

    override fun removeRule(
        actor: Actor,
        ruleId: UUID,
    ) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators set link rules")
        if (!repository.removeRule(
                actor.tenant,
                ruleId,
            )
        ) {
            throw NotFoundException("RULE_NOT_FOUND", "There's no such rule")
        }
    }
}
