package com.plm.types.api

import com.plm.platform.actor.Actor
import java.util.UUID

@JvmInline
value class TypeId(
    val value: UUID,
) {
    override fun toString() = value.toString()

    companion object {
        /** Seeded built-in base types for the default tenant. */
        val DOCUMENT = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000101"))
        val PART = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000102"))
    }
}

/** The kind of object a type describes. A subtype always keeps its parent's base class. */
enum class BaseClass { DOCUMENT, PART }

data class TypeDefinition(
    val id: TypeId,
    val parentId: TypeId?,
    val name: String,
    val displayName: String,
    val baseClass: BaseClass,
)

data class CreateSubtype(
    val parentId: TypeId,
    val name: String,
    val displayName: String,
)

interface TypeService {
    /** @throws com.plm.platform.error.NotFoundException `TYPE_NOT_FOUND` */
    fun get(
        actor: Actor,
        id: TypeId,
    ): TypeDefinition

    fun list(actor: Actor): List<TypeDefinition>

    /** @throws com.plm.platform.error.ConflictException `DUPLICATE_TYPE_NAME` */
    fun createSubtype(
        actor: Actor,
        command: CreateSubtype,
    ): TypeDefinition
}
