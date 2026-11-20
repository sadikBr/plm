package com.plm.types.infra.web

import com.plm.platform.actor.Actor
import com.plm.types.api.CreateSubtype
import com.plm.types.api.TypeDefinition
import com.plm.types.api.TypeId
import com.plm.types.api.TypeProperties
import com.plm.types.api.TypeService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/types")
internal class TypeController(
    private val types: TypeService,
) {
    @GetMapping
    fun list(actor: Actor) = types.list(actor).map(::TypeResponse)

    @GetMapping("/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = TypeResponse(types.get(actor, TypeId(id)))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: CreateTypeRequest,
    ) = TypeResponse(
        types.createSubtype(actor, CreateSubtype(TypeId(request.parentId), request.name, request.displayName)),
    )

    /** Changes a type's display name, translations, description, icon and whether it can be created. Site admins only. */
    @PutMapping("/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: TypePropertiesRequest,
    ) = TypeResponse(
        types.update(
            actor,
            TypeId(id),
            TypeProperties(
                request.displayName,
                request.description,
                request.labels.orEmpty(),
                request.icon,
                request.instantiable ?: true,
            ),
        ),
    )
}

internal data class TypePropertiesRequest(
    val displayName: String,
    val description: String? = null,
    /** Translations of the display name by language tag, e.g. `{"fr": "Pièce"}`. */
    val labels: Map<String, String>? = null,
    val icon: String? = null,
    /** False: an abstract type that only groups subtypes. */
    val instantiable: Boolean? = null,
)

internal data class CreateTypeRequest(
    val parentId: UUID,
    val name: String,
    val displayName: String,
)

internal data class TypeResponse(
    val id: UUID,
    val parentId: UUID?,
    val name: String,
    val displayName: String,
    val baseClass: String,
    val description: String?,
    val labels: Map<String, String>,
    val icon: String?,
    val instantiable: Boolean,
) {
    constructor(t: TypeDefinition) : this(
        t.id.value,
        t.parentId?.value,
        t.name,
        t.displayName,
        t.baseClass.name,
        t.description,
        t.labels,
        t.icon,
        t.instantiable,
    )
}
