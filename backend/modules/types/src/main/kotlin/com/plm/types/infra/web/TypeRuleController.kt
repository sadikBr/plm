package com.plm.types.infra.web

import com.plm.platform.actor.Actor
import com.plm.types.api.CreateNumberingScheme
import com.plm.types.api.NumberingScheme
import com.plm.types.api.NumberingSchemeId
import com.plm.types.api.RevisionScheme
import com.plm.types.api.TypeId
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeRuleSettings
import com.plm.types.api.TypeRules
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class TypeRuleController(
    private val rules: TypeRuleService,
) {
    /** Effective rules, with where each one comes from (`inheritedFrom`). */
    @GetMapping("/api/v1/types/{typeId}/rules")
    fun rules(
        actor: Actor,
        @PathVariable typeId: UUID,
    ) = TypeRulesResponse(rules.rules(actor, TypeId(typeId)))

    /** Sets this type's own overrides. Null fields inherit from the parent type. Site admins only. */
    @PutMapping("/api/v1/types/{typeId}/rules")
    fun update(
        actor: Actor,
        @PathVariable typeId: UUID,
        @RequestBody request: TypeRuleRequest,
    ) = TypeRulesResponse(
        rules.updateRules(
            actor,
            TypeId(typeId),
            TypeRuleSettings(
                request.numberingSchemeId?.let(::NumberingSchemeId),
                request.revisionScheme,
                request.allowManualNumber,
            ),
        ),
    )

    @GetMapping("/api/v1/numbering-schemes")
    fun schemes(actor: Actor) = rules.numberingSchemes(actor).map(::NumberingSchemeResponse)

    @PostMapping("/api/v1/numbering-schemes")
    @ResponseStatus(HttpStatus.CREATED)
    fun createScheme(
        actor: Actor,
        @RequestBody request: CreateNumberingSchemeRequest,
    ) = NumberingSchemeResponse(
        rules.createNumberingScheme(
            actor,
            CreateNumberingScheme(
                request.name,
                request.prefix ?: "",
                request.padding ?: 6,
                request.suffix ?: "",
                request.start ?: 1,
            ),
        ),
    )
}

internal data class TypeRuleRequest(
    val numberingSchemeId: UUID? = null,
    val revisionScheme: RevisionScheme? = null,
    val allowManualNumber: Boolean? = null,
)

internal data class TypeRulesResponse(
    val typeId: UUID,
    val numberingSchemeId: UUID?,
    val revisionScheme: RevisionScheme,
    val allowManualNumber: Boolean,
    val inheritedFrom: Map<String, UUID>,
) {
    constructor(r: TypeRules) : this(
        r.typeId.value,
        r.numberingSchemeId?.value,
        r.revisionScheme,
        r.allowManualNumber,
        r.inheritedFrom.mapValues { it.value.value },
    )
}

internal data class CreateNumberingSchemeRequest(
    val name: String,
    val prefix: String? = null,
    val padding: Int? = null,
    val suffix: String? = null,
    val start: Long? = null,
)

internal data class NumberingSchemeResponse(
    val id: UUID,
    val name: String,
    val prefix: String,
    val padding: Int,
    val suffix: String,
    val nextValue: Long,
    /** What the next number will look like. */
    val example: String,
) {
    constructor(s: NumberingScheme) : this(s.id.value, s.name, s.prefix, s.padding, s.suffix, s.nextValue, s.example)
}
