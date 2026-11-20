package com.plm.access.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

@JvmInline
value class ContextId(
    val value: UUID,
) {
    override fun toString() = value.toString()

    companion object {
        /** The site context seeded for the default tenant. */
        val DEFAULT_SITE = ContextId(UUID.fromString("00000000-0000-7000-8000-000000000011"))
    }
}

enum class ContextKind {
    SITE,
    ORGANIZATION,
    PRODUCT,
    LIBRARY,
    PROJECT,
    ;

    /** Kinds that may be created directly under this one. */
    fun allowedChildren(): Set<ContextKind> =
        when (this) {
            SITE -> setOf(ORGANIZATION)
            ORGANIZATION -> setOf(PRODUCT, LIBRARY, PROJECT)
            PRODUCT, LIBRARY, PROJECT -> emptySet()
        }

    /** Whether managed objects (documents, parts) may live directly in this kind of context. */
    val holdsObjects: Boolean get() = this == PRODUCT || this == LIBRARY || this == PROJECT
}

data class Context(
    val id: ContextId,
    val parentId: ContextId?,
    val kind: ContextKind,
    val name: String,
    val createdAt: Instant,
    val createdBy: String,
)

data class CreateContext(
    val parentId: ContextId,
    val kind: ContextKind,
    val name: String,
)

interface ContextService {
    /** @throws com.plm.platform.error.NotFoundException `CONTEXT_NOT_FOUND` */
    fun get(
        actor: Actor,
        id: ContextId,
    ): Context

    fun children(
        actor: Actor,
        parentId: ContextId,
    ): List<Context>

    /**
     * @throws com.plm.platform.error.ValidationException `INVALID_CONTEXT_HIERARCHY`, `INVALID_CONTEXT_NAME`
     * @throws com.plm.platform.error.ConflictException `DUPLICATE_CONTEXT_NAME`
     */
    fun create(
        actor: Actor,
        command: CreateContext,
    ): Context
}
