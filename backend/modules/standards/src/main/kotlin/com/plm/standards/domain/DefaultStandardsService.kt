package com.plm.standards.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Permission
import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.standards.api.AddExternalLink
import com.plm.standards.api.ExternalLink
import com.plm.standards.api.ExternalLinkTypes
import com.plm.standards.api.StandardsService
import com.plm.standards.api.StepExport
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

@Repository
internal class ExternalLinkRepository(
    private val jdbc: JdbcClient,
) {
    fun links(objectId: UUID): List<ExternalLink> =
        jdbc
            .sql("select * from external_link where object_id = :o order by link_type, created_at")
            .param("o", objectId)
            .query { rs, _ ->
                ExternalLink(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("object_id", UUID::class.java),
                    rs.getString("link_type"),
                    rs.getString("url"),
                    rs.getString("title"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("created_by"),
                )
            }.list()

    fun insert(
        tenant: UUID,
        link: ExternalLink,
    ) {
        jdbc
            .sql(
                """
                insert into external_link (id, tenant_id, object_id, link_type, url, title, created_at, created_by)
                values (:id, :t, :o, :type, :url, :title, :at, :by)
                """,
            ).param("id", link.id)
            .param("t", tenant)
            .param("o", link.objectId)
            .param("type", link.linkType)
            .param("url", link.url)
            .param("title", link.title)
            .param("at", Timestamp.from(link.createdAt))
            .param("by", link.createdBy)
            .update()
    }

    fun delete(
        objectId: UUID,
        id: UUID,
    ): Int =
        jdbc
            .sql(
                "delete from external_link where object_id = :o and id = :id",
            ).param("o", objectId)
            .param("id", id)
            .update()
}

@Service
@Transactional
internal class DefaultStandardsService(
    private val exporter: StepExporter,
    private val links: ExternalLinkRepository,
    private val versioning: VersioningService,
    private val access: AccessService,
    private val clock: Clock,
) : StandardsService {
    @Transactional(readOnly = true)
    override fun stepExport(
        actor: Actor,
        rootId: UUID,
        released: Boolean,
        withFiles: Boolean,
    ): StepExport = exporter.export(actor, rootId, released, withFiles)

    @Transactional(readOnly = true)
    override fun links(
        actor: Actor,
        objectId: UUID,
    ): List<ExternalLink> {
        versioning.get(actor, MasterId(objectId))
        return links.links(objectId)
    }

    override fun addLink(
        actor: Actor,
        objectId: UUID,
        command: AddExternalLink,
    ): ExternalLink {
        val obj = versioning.get(actor, MasterId(objectId))
        access.require(
            actor,
            AccessTarget(obj.contextId, obj.typeId, obj.latest.state, obj.id.value),
            Permission.MODIFY,
        )
        val type = ExternalLinkTypes.ALL.firstOrNull { it.key == command.linkType }
        val url = command.url.trim()
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        val errors = mutableListOf<String>()
        if (type == null) errors += "Unknown link type ${command.linkType}"
        if (scheme != "https" && scheme != "http") errors += "The link must be an http(s) address"
        if (url.length > 2000) errors += "The address is too long"
        if (errors.isNotEmpty()) {
            throw ValidationException(
                "INVALID_EXTERNAL_LINK",
                errors.joinToString("; "),
                mapOf(
                    "errors" to errors,
                ),
            )
        }
        val link =
            ExternalLink(
                UuidV7.generate(clock),
                objectId,
                type!!.key,
                url,
                command.title
                    ?.trim()
                    ?.take(300)
                    ?.ifEmpty { null },
                clock.instant(),
                actor.principal.value,
            )
        try {
            links.insert(actor.tenant.value, link)
        } catch (e: DuplicateKeyException) {
            throw ValidationException("INVALID_EXTERNAL_LINK", "That link is already there").apply { initCause(e) }
        }
        return link
    }

    override fun removeLink(
        actor: Actor,
        objectId: UUID,
        linkId: UUID,
    ) {
        val obj = versioning.get(actor, MasterId(objectId))
        access.require(
            actor,
            AccessTarget(obj.contextId, obj.typeId, obj.latest.state, obj.id.value),
            Permission.MODIFY,
        )
        if (links.delete(objectId, linkId) ==
            0
        ) {
            throw NotFoundException("EXTERNAL_LINK_NOT_FOUND", "There's no such link")
        }
    }
}
