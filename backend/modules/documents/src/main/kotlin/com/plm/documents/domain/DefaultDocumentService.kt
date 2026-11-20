package com.plm.documents.domain

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.documents.api.DocumentService
import com.plm.documents.api.DocumentTemplate
import com.plm.documents.api.DocumentTemplateChanged
import com.plm.documents.api.NewFromTemplate
import com.plm.documents.api.ObjectCopied
import com.plm.documents.api.SaveAs
import com.plm.documents.api.TemplateSettings
import com.plm.documents.infra.DocumentTemplateRepository
import com.plm.documents.infra.TemplateRow
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.platform.id.UuidV7
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultDocumentService(
    private val templates: DocumentTemplateRepository,
    private val versioning: VersioningService,
    private val content: ContentService,
    private val contexts: ContextService,
    private val events: DomainEvents,
    private val clock: Clock,
) : DocumentService {
    @Transactional(readOnly = true)
    override fun templates(
        actor: Actor,
        contextId: UUID?,
        typeId: UUID?,
    ): List<DocumentTemplate> {
        // Templates of an organization are for its contexts; without a context, administrators see all.
        val organization =
            contextId?.let { id ->
                contexts
                    .path(actor, ContextId(id))
                    .firstOrNull { it.kind == ContextKind.ORGANIZATION }
                    ?.id
                    ?.value
            }
        return templates
            .all(actor.tenant)
            .filter {
                contextId == null &&
                    actor.siteAdmin ||
                    (it.active && (it.organizationId == null || it.organizationId == organization))
            }.mapNotNull { view(actor, it) }
            .filter { typeId == null || it.typeId == typeId }
    }

    /** A template as the actor sees it; null when they can't read its object. */
    private fun view(
        actor: Actor,
        t: TemplateRow,
    ): DocumentTemplate? {
        val obj = runCatching { versioning.get(actor, MasterId(t.masterId)) }.getOrNull() ?: return null
        val primary =
            runCatching { content.list(actor, obj.id) }.getOrDefault(emptyList()).firstOrNull {
                it.role ==
                    ContentRole.PRIMARY
            }
        return DocumentTemplate(
            id = t.id,
            name = t.name,
            description = t.description,
            objectId = obj.id.value,
            number = obj.number,
            version = obj.latest.version,
            typeId = obj.typeId.value,
            fileName = primary?.fileName,
            organizationId = t.organizationId,
            active = t.active,
            updatedAt = t.updatedAt,
            updatedBy = t.updatedBy,
        )
    }

    override fun createTemplate(
        actor: Actor,
        settings: TemplateSettings,
    ): DocumentTemplate = saveTemplate(actor, null, settings)

    override fun updateTemplate(
        actor: Actor,
        id: UUID,
        settings: TemplateSettings,
    ): DocumentTemplate = saveTemplate(actor, id, settings)

    override fun deleteTemplate(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        val t = templates.find(actor.tenant, id) ?: notFound()
        templates.delete(actor.tenant, id)
        events.publish(actor, DocumentTemplateChanged(id, t.name, "DELETED"))
    }

    private fun saveTemplate(
        actor: Actor,
        id: UUID?,
        settings: TemplateSettings,
    ): DocumentTemplate {
        requireAdmin(actor)
        if (id != null) templates.find(actor.tenant, id) ?: notFound()
        val name = settings.name.trim()
        if (name.isEmpty() ||
            name.length > 100
        ) {
            throw ValidationException("INVALID_TEMPLATE", "A template needs a name")
        }
        val obj = versioning.get(actor, MasterId(settings.objectId))
        if (settings.organizationId != null) {
            val org = contexts.get(actor, ContextId(settings.organizationId))
            if (org.kind !=
                ContextKind.ORGANIZATION
            ) {
                throw ValidationException("INVALID_TEMPLATE", "${org.name} isn't an organization")
            }
        }
        when (templates.conflict(actor.tenant, name, obj.id.value, id)) {
            "name" -> throw ConflictException("DUPLICATE_TEMPLATE", "There's already a template named $name")
            "object" -> throw ConflictException("DUPLICATE_TEMPLATE", "${obj.number} is already a template")
        }
        val row =
            TemplateRow(
                id = id ?: UuidV7.generate(clock),
                masterId = obj.id.value,
                name = name,
                description = settings.description?.trim()?.ifEmpty { null },
                organizationId = settings.organizationId,
                active = settings.active,
                updatedAt = clock.instant(),
                updatedBy = actor.principal.value,
            )
        templates.save(actor.tenant, row, clock.instant())
        events.publish(actor, DocumentTemplateChanged(row.id, name, if (id == null) "CREATED" else "UPDATED"))
        return view(actor, row)!!
    }

    override fun newFromTemplate(
        actor: Actor,
        request: NewFromTemplate,
    ): VersionedObject {
        val t =
            templates(actor, request.contextId).firstOrNull { it.id == request.templateId }
                ?: throw NotFoundException("TEMPLATE_NOT_FOUND", "There's no such template here")
        val source = versioning.get(actor, MasterId(t.objectId))
        return copy(
            actor,
            source,
            request.contextId,
            request.name,
            request.number,
            source.latest.attributes + request.attributes,
            true,
            "TEMPLATE",
        )
    }

    override fun saveAs(
        actor: Actor,
        objectId: MasterId,
        request: SaveAs,
    ): VersionedObject {
        val source = versioning.get(actor, objectId)
        return copy(
            actor,
            source,
            request.contextId ?: source.contextId.value,
            request.name,
            request.number,
            source.latest.attributes,
            request.withFiles,
            "SAVE_AS",
        )
    }

    private fun copy(
        actor: Actor,
        source: VersionedObject,
        contextId: UUID,
        name: String,
        number: String?,
        attributes: Map<String, Any?>,
        withFiles: Boolean,
        how: String,
    ): VersionedObject {
        val created =
            versioning.create(
                actor,
                CreateVersionedObject(
                    source.typeId,
                    ContextId(contextId),
                    name,
                    number?.trim()?.ifEmpty { null },
                    attributes,
                ),
            )
        val files = if (withFiles) content.copyLatest(actor, source.id, created.id) else 0
        events.publish(
            actor,
            ObjectCopied(
                created.id.value,
                created.number,
                source.id.value,
                source.number,
                source.latest.version,
                how,
                files,
            ),
        )
        return created
    }

    private fun notFound(): Nothing = throw NotFoundException("TEMPLATE_NOT_FOUND", "There's no such template")

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators manage document templates",
            )
        }
    }
}
