package com.plm.connectors.domain

import com.plm.access.api.ContextId
import com.plm.cad.api.AddDependency
import com.plm.cad.api.Associate
import com.plm.cad.api.AssociationKind
import com.plm.cad.api.AssociationService
import com.plm.cad.api.CadKind
import com.plm.cad.api.CadService
import com.plm.connectors.api.ConnectorService
import com.plm.connectors.api.ManifestDependency
import com.plm.connectors.api.ManifestModel
import com.plm.connectors.api.ModelFile
import com.plm.connectors.api.SaveManifest
import com.plm.connectors.api.SavedModel
import com.plm.content.api.AttachContent
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.types.api.AttributeService
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersionedObject
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val MAX_MODELS = 2000

@Service
@Transactional
internal class DefaultConnectorService(
    private val versioning: VersioningService,
    private val content: ContentService,
    private val cad: CadService,
    private val associations: AssociationService,
    private val attributes: AttributeService,
) : ConnectorService {
    private fun typeOf(kind: CadKind) =
        when (kind) {
            CadKind.PART -> TypeId.CAD_PART
            CadKind.ASSEMBLY -> TypeId.CAD_ASSEMBLY
            CadKind.DRAWING -> TypeId.CAD_DRAWING
        }

    override fun save(
        actor: Actor,
        manifest: SaveManifest,
    ): List<SavedModel> {
        val models = manifest.models
        if (models.isEmpty() || models.size > MAX_MODELS) {
            throw ValidationException("INVALID_MANIFEST", "A save holds 1 to $MAX_MODELS models")
        }
        models.groupBy { it.key }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw ValidationException("INVALID_MANIFEST", "“$it” appears twice in the manifest")
        }
        val application =
            manifest.application
                .trim()
                .take(200)
                .ifEmpty { "Connector" }
        // 1. Every model exists and is checked out to the person.
        val ids = linkedMapOf<String, MasterId>()
        val created = mutableSetOf<String>()
        for (m in models) {
            named(m) {
                val obj =
                    if (m.id != null) {
                        versioning.get(actor, MasterId(m.id))
                    } else {
                        created += m.key
                        versioning.create(
                            actor,
                            CreateVersionedObject(
                                typeOf(m.kind),
                                ContextId(manifest.contextId),
                                m.name.trim().ifEmpty { m.key.substringAfterLast('/').substringBeforeLast('.') },
                                m.number?.trim()?.ifEmpty { null },
                                attributes =
                                    filtered(
                                        actor,
                                        typeOf(m.kind),
                                        mapOf("authoringApplication" to application),
                                    ),
                                folderId = manifest.folderId,
                            ),
                        )
                    }
                ids[m.key] = obj.id
            }
        }
        // Only what changed is checked out: a model with no new file, the same values and the same
        // dependencies keeps its version.
        val touched = mutableSetOf<String>()
        for (m in models) {
            named(m) {
                val id = ids.getValue(m.key)
                val obj = versioning.get(actor, id)
                val changed =
                    m.key in created ||
                        m.uploadId != null ||
                        obj.checkedOutBy == actor.principal.value ||
                        filtered(actor, obj.typeId, m.attributes + ("authoringApplication" to application))
                            .any { (k, v) -> obj.latest.attributes[k] != v } ||
                        dependencyKeys(m, ids) !=
                        cad
                            .dependencies(
                                actor,
                                id.value,
                            ).map { Dep(it.child.id, it.kind.name, it.required, it.occurrence, it.transform) }
                if (!changed) return@named
                when (obj.checkedOutBy) {
                    null -> versioning.checkOut(actor, obj.id)
                    actor.principal.value -> Unit
                    else -> throw ConflictException(
                        "CHECKED_OUT_BY_ANOTHER_USER",
                        "${obj.number} is checked out by someone else",
                    )
                }
                touched += m.key
            }
        }
        // 2. Files, values and dependencies into the working copies.
        for (m in models.filter { it.key in touched }) {
            named(m) {
                val id = ids.getValue(m.key)
                if (m.uploadId != null) content.attach(actor, id, AttachContent(m.uploadId, ContentRole.PRIMARY))
                val obj = versioning.get(actor, id)
                val wc = versioning.workingCopy(actor, id)
                val values = filtered(actor, obj.typeId, m.attributes + ("authoringApplication" to application))
                if (values.any { (k, v) -> wc.attributes[k] != v }) {
                    versioning.updateWorkingCopy(
                        actor,
                        id,
                        wc.attributes + values,
                    )
                }
                cad.dependencies(actor, id.value, workingCopy = true).forEach { cad.remove(actor, id.value, it.id) }
                for (d in m.dependencies) {
                    val child = d.id ?: d.key?.let { ids[it]?.value }
                    if (child == null) {
                        throw ValidationException(
                            "UNKNOWN_DEPENDENCY",
                            "It depends on “${d.key ?: d.id}”, which is neither in the save nor given by id",
                        )
                    }
                    cad.add(actor, id.value, AddDependency(child, d.kind, d.required, d.occurrence, d.transform))
                }
            }
        }
        // 3. Check in (all together), then the parts they represent.
        if (manifest.checkIn) {
            val comment = manifest.comment?.trim()?.ifEmpty { null } ?: "Saved from $application"
            for (m in models.filter { it.key in touched }) {
                named(
                    m,
                ) { versioning.checkIn(actor, ids.getValue(m.key), comment) }
            }
        }
        return models.map { m ->
            named(m) {
                val id = ids.getValue(m.key)
                val part = m.part?.let { associate(actor, it, id, m, manifest) }
                val obj = versioning.get(actor, id)
                val version =
                    if (obj.checkedOutBy ==
                        actor.principal.value
                    ) {
                        versioning.workingCopy(actor, id).version
                    } else {
                        obj.latest.version
                    }
                SavedModel(m.key, id.value, obj.number, version, m.key in created, part?.id?.value, part?.number)
            }
        }
    }

    /** Makes [cad] the owning model of the part asked for, creating it if needed. */
    private fun associate(
        actor: Actor,
        ref: com.plm.connectors.api.ManifestPart,
        cad: MasterId,
        m: ManifestModel,
        manifest: SaveManifest,
    ): VersionedObject {
        val part =
            when {
                ref.id != null -> versioning.get(actor, MasterId(ref.id))
                ref.number != null ->
                    versioning
                        .list(actor, ObjectQuery(text = ref.number, limit = 20))
                        .firstOrNull { it.number.equals(ref.number, ignoreCase = true) }
                        ?: throw NotFoundException("PART_NOT_FOUND", "There's no part ${ref.number}")
                ref.create ->
                    versioning.create(
                        actor,
                        CreateVersionedObject(
                            TypeId.PART,
                            ContextId(manifest.contextId),
                            ref.name ?: m.name,
                            folderId = manifest.folderId,
                        ),
                    )
                else -> throw ValidationException(
                    "INVALID_MANIFEST",
                    "Name the part by id or number, or ask to create it",
                )
            }
        if (associations.associations(actor, part.id.value).any { it.model.id == cad.value }) return part
        val mine = part.checkedOutBy == actor.principal.value
        if (!mine) versioning.checkOut(actor, part.id)
        associations.associate(actor, part.id.value, Associate(cad.value, AssociationKind.OWNER))
        if (!mine &&
            manifest.checkIn
        ) {
            versioning.checkIn(actor, part.id, "Owned by its model, saved from ${manifest.application}")
        }
        return versioning.get(actor, part.id)
    }

    @Transactional(readOnly = true)
    override fun files(
        actor: Actor,
        id: UUID,
    ): List<ModelFile> {
        val out = linkedMapOf<UUID, ModelFile>()

        fun visit(model: UUID) {
            if (model in out || out.size >= MAX_MODELS) return
            val obj = versioning.get(actor, MasterId(model))
            val mine = obj.checkedOutBy == actor.principal.value
            val iteration = if (mine) versioning.workingCopy(actor, obj.id) else obj.latest
            val file =
                content
                    .list(actor, obj.id, if (mine) null else iteration.id.value, workingCopy = mine)
                    .firstOrNull { it.role == ContentRole.PRIMARY }
            val deps = cad.dependencies(actor, model, workingCopy = mine).filter { it.child.readable }
            out[model] =
                ModelFile(
                    obj.id.value,
                    obj.number,
                    obj.name,
                    cad.kindOf(actor, model),
                    iteration.version,
                    iteration.id.value,
                    file?.fileName,
                    file?.sha256,
                    file?.let { content.download(actor, it.id).url },
                    deps.map {
                        ManifestDependency(
                            id = it.child.id,
                            kind = it.kind,
                            required = it.required,
                            occurrence = it.occurrence,
                            transform = it.transform,
                        )
                    },
                )
            deps.forEach { visit(it.child.id) }
        }
        visit(id)
        return out.values.toList()
    }

    private data class Dep(
        val child: UUID?,
        val kind: String,
        val required: Boolean,
        val occurrence: String?,
        val transform: List<Double>?,
    )

    /** A model's dependencies as the manifest asks for them (children resolved), to compare with the stored ones. */
    private fun dependencyKeys(
        m: ManifestModel,
        ids: Map<String, MasterId>,
    ) = m.dependencies.map {
        Dep(
            it.id ?: it.key?.let { k ->
                ids[k]?.value
            },
            it.kind.name,
            it.required,
            it.occurrence,
            it.transform,
        )
    }

    /** Only the values the type has attributes for. */
    private fun filtered(
        actor: Actor,
        type: TypeId,
        values: Map<String, Any?>,
    ): Map<String, Any?> {
        val names = attributes.effective(actor, type).map { it.name }.toSet()
        return values.filterKeys { it in names }
    }

    private fun <T> named(
        m: ManifestModel,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: PlmException) {
            val message = "${m.key}: ${e.message}"
            throw when (e) {
                is NotFoundException -> NotFoundException(e.code, message, e.details)
                is ConflictException -> ConflictException(e.code, message, e.details)
                is ValidationException -> ValidationException(e.code, message, e.details)
                is ForbiddenException -> ForbiddenException(e.code, message, e.details)
            }
        }
}
