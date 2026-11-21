package com.plm.types.infra.web

import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeId
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeSettings
import com.plm.types.api.DataType
import com.plm.types.api.DefineAttribute
import com.plm.types.api.Enumeration
import com.plm.types.api.EnumerationId
import com.plm.types.api.EnumerationService
import com.plm.types.api.EnumerationValue
import com.plm.types.api.TypeId
import com.plm.types.api.UnitOfMeasure
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

@RestController
@RequestMapping("/api/v1/types/{typeId}/attributes")
internal class AttributeController(
    private val attributes: AttributeService,
) {
    /** Effective attributes, including inherited ones (`inherited: true`). Forms are rendered from this. */
    @GetMapping
    fun list(
        actor: Actor,
        @PathVariable typeId: UUID,
        @RequestParam(defaultValue = "false") includeInactive: Boolean,
    ) = attributes.effective(actor, TypeId(typeId), includeInactive).map { AttributeResponse(it, TypeId(typeId)) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun define(
        actor: Actor,
        @PathVariable typeId: UUID,
        @RequestBody request: DefineAttributeRequest,
    ) = AttributeResponse(
        attributes.define(
            actor,
            DefineAttribute(
                typeId = TypeId(typeId),
                name = request.name,
                dataType = request.dataType,
                multiValued = request.multiValued ?: false,
                enumerationId = request.enumerationId?.let(::EnumerationId),
                dimension = request.dimension,
                settings = request.settings(),
                referenceTypeId = request.referenceTypeId?.let(::TypeId),
            ),
        ),
        TypeId(typeId),
    )

    /** Replaces the changeable settings. Name, data type, multi-valued, enumeration and dimension are fixed. */
    @PutMapping("/{attributeId}")
    fun update(
        actor: Actor,
        @PathVariable typeId: UUID,
        @PathVariable attributeId: UUID,
        @RequestBody request: AttributeSettingsRequest,
    ): AttributeResponse {
        val owned =
            attributes.effective(actor, TypeId(typeId), includeInactive = true).any {
                it.id.value == attributeId &&
                    it.typeId.value == typeId
            }
        if (!owned) {
            throw NotFoundException(
                "ATTRIBUTE_NOT_FOUND",
                "Type $typeId does not define attribute $attributeId (it may be inherited: change it on the type that defines it)",
            )
        }
        return AttributeResponse(attributes.update(actor, AttributeId(attributeId), request.settings()), TypeId(typeId))
    }
}

@RestController
internal class GlobalAttributeController(
    private val attributes: AttributeService,
) {
    /** Global attributes and the types that use them. */
    @GetMapping("/api/v1/attributes/global")
    fun list(actor: Actor) =
        attributes.globals(actor).map {
            GlobalAttributeResponse(
                AttributeResponse(it.definition, TypeId.GLOBAL),
                it.usedBy.map { t ->
                    t.value
                },
            )
        }

    /** Defines a global attribute, to be added to any number of types. Site admins only. */
    @PostMapping("/api/v1/attributes/global")
    @ResponseStatus(HttpStatus.CREATED)
    fun define(
        actor: Actor,
        @RequestBody request: DefineAttributeRequest,
    ) = AttributeResponse(
        attributes.defineGlobal(
            actor,
            DefineAttribute(
                TypeId.GLOBAL,
                request.name,
                request.dataType,
                request.multiValued ?: false,
                request.enumerationId?.let(::EnumerationId),
                request.dimension,
                request.settings(),
                request.referenceTypeId?.let(::TypeId),
            ),
        ),
        TypeId.GLOBAL,
    )

    /** Changes a global attribute's settings, for every type using it. Site admins only. */
    @PutMapping("/api/v1/attributes/global/{attributeId}")
    fun update(
        actor: Actor,
        @PathVariable attributeId: UUID,
        @RequestBody request: AttributeSettingsRequest,
    ) = AttributeResponse(attributes.update(actor, AttributeId(attributeId), request.settings()), TypeId.GLOBAL)

    /** Adds a global attribute to a type (and its subtypes). */
    @PutMapping("/api/v1/types/{typeId}/global-attributes/{attributeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun attach(
        actor: Actor,
        @PathVariable typeId: UUID,
        @PathVariable attributeId: UUID,
    ) = attributes.attachGlobal(actor, AttributeId(attributeId), TypeId(typeId))

    @DeleteMapping("/api/v1/types/{typeId}/global-attributes/{attributeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun detach(
        actor: Actor,
        @PathVariable typeId: UUID,
        @PathVariable attributeId: UUID,
    ) = attributes.detachGlobal(actor, AttributeId(attributeId), TypeId(typeId))
}

internal data class GlobalAttributeResponse(
    val attribute: AttributeResponse,
    val usedBy: List<UUID>,
)

@RestController
internal class ReferenceDataController(
    private val enumerations: EnumerationService,
) {
    @GetMapping("/api/v1/enumerations")
    fun list(actor: Actor) = enumerations.list(actor).map(::EnumerationResponse)

    @GetMapping("/api/v1/enumerations/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = EnumerationResponse(enumerations.get(actor, EnumerationId(id)))

    @PostMapping("/api/v1/enumerations")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: EnumerationRequest,
    ) = EnumerationResponse(
        enumerations.create(
            actor,
            request.name ?: "",
            request.labels,
            request.values.map {
                it.toValue()
            },
        ),
    )

    /** Replaces labels and the ordered value list. Existing keys can be deactivated, never removed. */
    @PutMapping("/api/v1/enumerations/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: EnumerationRequest,
    ) = EnumerationResponse(
        enumerations.update(
            actor,
            EnumerationId(id),
            request.labels,
            request.values.map {
                it.toValue()
            },
        ),
    )

    @GetMapping("/api/v1/units")
    fun units(actor: Actor) = enumerations.units(actor).map(::UnitResponse)

    /** Adds a unit; the first unit of a new dimension is its base unit. Site admins only. */
    @PostMapping("/api/v1/units")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUnit(
        actor: Actor,
        @RequestBody request: CreateUnitRequest,
    ) = UnitResponse(
        enumerations.createUnit(
            actor,
            UnitOfMeasure(
                request.code,
                request.dimension,
                request.labels,
                request.factor ?: BigDecimal.ONE,
                request.offset ?: BigDecimal.ZERO,
            ),
        ),
    )
}

internal data class AttributeResponse(
    val id: UUID,
    /** The type that defines it. */
    val typeId: UUID,
    /** True when defined on an ancestor of the requested type. */
    val inherited: Boolean,
    val name: String,
    val labels: Map<String, String>,
    val description: String?,
    val dataType: DataType,
    val multiValued: Boolean,
    val required: Boolean,
    val immutable: Boolean,
    val minLength: Int?,
    val maxLength: Int?,
    val pattern: String?,
    /** For QUANTITY: in the dimension's base unit. */
    val minValue: BigDecimal?,
    val maxValue: BigDecimal?,
    val enumerationId: UUID?,
    val dimension: String?,
    val displayUnit: String?,
    val defaultValue: Any?,
    val sortOrder: Int,
    val active: Boolean,
    val system: Boolean,
    /** Dependent value list: the attribute whose value decides the allowed values. */
    val dependsOn: String?,
    /** Dependent value list: parent value → allowed values. */
    val valueFilter: Map<String, List<String>>?,
    /** Defined once and shared by several types (changing it changes it everywhere). */
    val global: Boolean,
    /** Calculated attribute: the CEL formula that computes it (users can't set it). */
    val formula: String?,
    /** For a REFERENCE: the type the object it points to must have (or a subtype). */
    val referenceTypeId: UUID?,
    /** Alias attribute: `reference.property`, the value of the referenced object it shows. */
    val aliasOf: String?,
) {
    constructor(d: AttributeDefinition, requestedType: TypeId) : this(
        d.id.value,
        d.typeId.value,
        d.typeId != requestedType,
        d.name,
        d.labels,
        d.description,
        d.dataType,
        d.multiValued,
        d.required,
        d.immutable,
        d.minLength,
        d.maxLength,
        d.pattern,
        d.minValue,
        d.maxValue,
        d.enumerationId?.value,
        d.dimension,
        d.displayUnit,
        d.defaultValue,
        d.sortOrder,
        d.active,
        d.system,
        d.dependsOn,
        d.valueFilter,
        d.global,
        d.formula,
        d.referenceTypeId?.value,
        d.aliasOf,
    )
}

internal data class DefineAttributeRequest(
    val name: String,
    val dataType: DataType,
    val multiValued: Boolean? = null,
    val enumerationId: UUID? = null,
    /** For QUANTITY, e.g. LENGTH, MASS. See GET /api/v1/units. */
    val dimension: String? = null,
    val labels: Map<String, String>,
    val description: String? = null,
    val required: Boolean? = null,
    val immutable: Boolean? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val minValue: BigDecimal? = null,
    val maxValue: BigDecimal? = null,
    val displayUnit: String? = null,
    val defaultValue: Any? = null,
    val sortOrder: Int? = null,
    val dependsOn: String? = null,
    val valueFilter: Map<String, List<String>>? = null,
    val formula: String? = null,
    /** For REFERENCE: the type the object must have (or a subtype); null for any. */
    val referenceTypeId: UUID? = null,
    /** Alias attribute: `reference.property`. */
    val aliasOf: String? = null,
) {
    fun settings() =
        AttributeSettings(
            labels,
            description,
            required ?: false,
            immutable ?: false,
            minLength,
            maxLength,
            pattern,
            minValue,
            maxValue,
            displayUnit,
            defaultValue,
            sortOrder ?: 0,
            active = true,
            dependsOn = dependsOn,
            valueFilter = valueFilter,
            formula = formula,
            aliasOf = aliasOf,
        )
}

internal data class AttributeSettingsRequest(
    val labels: Map<String, String>,
    val description: String? = null,
    val required: Boolean? = null,
    val immutable: Boolean? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val minValue: BigDecimal? = null,
    val maxValue: BigDecimal? = null,
    val displayUnit: String? = null,
    val defaultValue: Any? = null,
    val sortOrder: Int? = null,
    val active: Boolean? = null,
    val dependsOn: String? = null,
    val valueFilter: Map<String, List<String>>? = null,
    val formula: String? = null,
    val aliasOf: String? = null,
) {
    fun settings() =
        AttributeSettings(
            labels,
            description,
            required ?: false,
            immutable ?: false,
            minLength,
            maxLength,
            pattern,
            minValue,
            maxValue,
            displayUnit,
            defaultValue,
            sortOrder ?: 0,
            active ?: true,
            dependsOn,
            valueFilter,
            formula,
            aliasOf,
        )
}

internal data class EnumerationValueDto(
    val key: String,
    val labels: Map<String, String>,
    val active: Boolean? = null,
) {
    fun toValue() = EnumerationValue(key, labels, active ?: true)
}

internal data class EnumerationRequest(
    /** Required when creating; ignored when updating (names never change). */
    val name: String? = null,
    val labels: Map<String, String>,
    val values: List<EnumerationValueDto>,
)

internal data class EnumerationResponse(
    val id: UUID,
    val name: String,
    val labels: Map<String, String>,
    val system: Boolean,
    val values: List<EnumerationValueDto>,
) {
    constructor(e: Enumeration) : this(
        e.id.value,
        e.name,
        e.labels,
        e.system,
        e.values.map { EnumerationValueDto(it.key, it.labels, it.active) },
    )
}

internal data class UnitResponse(
    val code: String,
    val dimension: String,
    val labels: Map<String, String>,
    val factor: BigDecimal,
    val offset: BigDecimal,
    /** Shipped out of the box (as opposed to added by an administrator). */
    val system: Boolean,
) {
    constructor(u: UnitOfMeasure) : this(u.code, u.dimension, u.labels, u.factor, u.offset, u.system)
}

internal data class CreateUnitRequest(
    /** e.g. `kN·m`. */
    val code: String,
    /** e.g. `TORQUE`; an existing dimension or a new one. */
    val dimension: String,
    val labels: Map<String, String>,
    /** value in the base unit = value × factor + offset. Default 1. */
    val factor: BigDecimal? = null,
    val offset: BigDecimal? = null,
)
