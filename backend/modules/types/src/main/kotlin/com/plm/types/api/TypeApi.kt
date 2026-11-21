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

        /** Change objects (roadmap 5.8): the root and its four kinds. */
        val CHANGE = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000103"))
        val PROBLEM_REPORT = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000104"))
        val CHANGE_REQUEST = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000105"))
        val CHANGE_NOTICE = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000106"))
        val CHANGE_TASK = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000107"))
        val VARIANCE = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000108"))

        /** CAD documents (roadmap 6.1): the root and its three kinds. */
        val CAD_DOCUMENT = TypeId(UUID.fromString("00000000-0000-7000-8000-000000000109"))
        val CAD_PART = TypeId(UUID.fromString("00000000-0000-7000-8000-00000000010a"))
        val CAD_ASSEMBLY = TypeId(UUID.fromString("00000000-0000-7000-8000-00000000010b"))
        val CAD_DRAWING = TypeId(UUID.fromString("00000000-0000-7000-8000-00000000010c"))

        /** Stands for "no type" on a global attribute's own definition (it is used by types, not defined on one). */
        val GLOBAL = TypeId(UUID(0, 0))
    }
}

/** The kind of object a type describes. A subtype always keeps its parent's base class. */
enum class BaseClass { DOCUMENT, PART, CHANGE }

data class TypeDefinition(
    val id: TypeId,
    val parentId: TypeId?,
    val name: String,
    /** The English display name. */
    val displayName: String,
    val baseClass: BaseClass,
    val description: String? = null,
    /** Translations of the display name, by language tag (`fr`, `de`…). */
    val labels: Map<String, String> = emptyMap(),
    /** An icon key from the UI's icon set (e.g. `bolt`); null: the base class's icon. */
    val icon: String? = null,
    /** False for abstract types that only group subtypes: they can't be created. */
    val instantiable: Boolean = true,
)

/** The editable properties of a type. The internal name, parent and base class are fixed. */
data class TypeProperties(
    val displayName: String,
    val description: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val icon: String? = null,
    val instantiable: Boolean = true,
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

    /** [id] and its ancestors, root first. Other modules use it to resolve settings inherited along the type tree. */
    fun lineage(
        actor: Actor,
        id: TypeId,
    ): List<TypeId>

    /**
     * Site administrators only.
     *
     * @throws com.plm.platform.error.ForbiddenException `ADMIN_REQUIRED`
     * @throws com.plm.platform.error.ConflictException `DUPLICATE_TYPE_NAME`
     */
    fun createSubtype(
        actor: Actor,
        command: CreateSubtype,
    ): TypeDefinition

    /** Site administrators only. `INVALID_TYPE_PROPERTIES`. */
    fun update(
        actor: Actor,
        id: TypeId,
        properties: TypeProperties,
    ): TypeDefinition
}
