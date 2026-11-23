package com.plm.bom.domain

import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.PastUsage
import com.plm.bom.api.StructureHistoryImportService
import com.plm.bom.infra.BomRepository
import com.plm.bom.infra.DocumentLinkRepository
import com.plm.bom.infra.LinkRow
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultStructureHistoryImportService(
    private val usages: BomRepository,
    private val links: DocumentLinkRepository,
    private val versioning: VersioningService,
    private val types: TypeService,
    private val clock: Clock,
) : StructureHistoryImportService {
    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators bring in history")
    }

    private fun base(
        actor: Actor,
        id: MasterId,
    ) = types.get(actor, versioning.get(actor, id).typeId).baseClass

    override fun importUsages(
        actor: Actor,
        parentId: MasterId,
        parentIterationId: UUID,
        lines: List<PastUsage>,
    ) {
        requireAdmin(actor)
        if (base(actor, parentId) !=
            BaseClass.PART
        ) {
            throw ValidationException("NOT_A_PART", "Only parts have structure")
        }
        // After the lines already there (an update adds to them).
        val start = usages.links(parentIterationId).maxOfOrNull { it.lineNumber } ?: 0
        lines.forEachIndexed { i, line ->
            if (line.childId == parentId.value) throw ValidationException("INVALID_USAGE", "A part can't use itself")
            if (base(actor, MasterId(line.childId)) != BaseClass.PART) {
                throw ValidationException("NOT_A_PART", "Only parts go into a structure")
            }
            if (line.quantity.signum() <= 0) throw ValidationException("INVALID_USAGE", "The quantity must be above 0")
            usages.insert(
                actor.tenant,
                LinkRow(
                    UuidV7.generate(clock),
                    parentIterationId,
                    line.childId,
                    (start / 10 + i + 1) * 10,
                    line.findNumber,
                    line.quantity,
                    line.unit ?: "each",
                    line.notes,
                    clock.instant(),
                    actor.principal.value,
                ),
            )
        }
    }

    override fun importDocumentLink(
        actor: Actor,
        partId: MasterId,
        partIterationId: UUID,
        documentId: MasterId,
        kind: DocumentLinkKind,
    ) {
        requireAdmin(actor)
        if (base(actor, partId) !=
            BaseClass.PART
        ) {
            throw ValidationException("NOT_A_PART", "Only parts link to documents")
        }
        val document = versioning.get(actor, documentId)
        if (types.get(actor, document.typeId).baseClass != BaseClass.DOCUMENT) {
            throw ValidationException("NOT_A_DOCUMENT", "${document.number} isn't a document")
        }
        if (links.exists(partIterationId, kind, documentId.value)) return
        links.insert(
            actor.tenant,
            UuidV7.generate(clock),
            partIterationId,
            kind,
            documentId.value,
            if (kind == DocumentLinkKind.DESCRIBED_BY) document.latest.id.value else null,
            clock.instant(),
            actor.principal.value,
        )
    }
}
