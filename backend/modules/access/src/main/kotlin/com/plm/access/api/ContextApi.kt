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

/**
 * A context is visible to a principal who is on its team, or on the team of any ancestor or
 * descendant (so users can navigate down to the products they work on). Invisible contexts are
 * reported as not found.
 */
interface ContextService {
    /** @throws com.plm.platform.error.NotFoundException `CONTEXT_NOT_FOUND` (also when not visible) */
    fun get(
        actor: Actor,
        id: ContextId,
    ): Context

    fun children(
        actor: Actor,
        parentId: ContextId,
    ): List<Context>

    /**
     * Organizations can only be created by site administrators. Other contexts need MANAGE on the
     * parent. The creator becomes MANAGER of the new context.
     *
     * @throws com.plm.platform.error.ForbiddenException `ACCESS_DENIED`
     * @throws com.plm.platform.error.ValidationException `INVALID_CONTEXT_HIERARCHY`, `INVALID_CONTEXT_NAME`
     * @throws com.plm.platform.error.ConflictException `DUPLICATE_CONTEXT_NAME`
     */
    fun create(
        actor: Actor,
        command: CreateContext,
    ): Context
}
