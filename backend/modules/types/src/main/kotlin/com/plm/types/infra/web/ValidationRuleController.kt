package com.plm.types.infra.web

import com.plm.platform.actor.Actor
import com.plm.types.api.TypeId
import com.plm.types.api.ValidationRule
import com.plm.types.api.ValidationRuleId
import com.plm.types.api.ValidationRuleService
import com.plm.types.api.ValidationRuleSettings
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
internal class ValidationRuleController(
    private val rules: ValidationRuleService,
) {
    /** Rules objects of this type must meet: inherited ones first (`inherited: true`), then its own. */
    @GetMapping("/api/v1/types/{id}/validation-rules")
    fun list(
        actor: Actor,
        @PathVariable id: UUID,
    ) = rules.rules(actor, TypeId(id)).map { ValidationRuleResponse(it, inherited = it.typeId.value != id) }

    @PostMapping("/api/v1/types/{id}/validation-rules")
    @ResponseStatus(HttpStatus.CREATED)
    fun define(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: ValidationRuleRequest,
    ) = ValidationRuleResponse(rules.define(actor, TypeId(id), request.settings()), inherited = false)

    @PutMapping("/api/v1/validation-rules/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: ValidationRuleRequest,
    ) = ValidationRuleResponse(rules.update(actor, ValidationRuleId(id), request.settings()), inherited = false)

    @DeleteMapping("/api/v1/validation-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = rules.delete(actor, ValidationRuleId(id))
}

internal data class ValidationRuleRequest(
    val name: String,
    val condition: String,
    val message: String,
    val attribute: String? = null,
    val active: Boolean? = null,
    val order: Int? = null,
) {
    fun settings() = ValidationRuleSettings(name, condition, message, attribute, active ?: true, order ?: 0)
}

internal data class ValidationRuleResponse(
    val id: UUID,
    val typeId: UUID,
    val name: String,
    val condition: String,
    val message: String,
    val attribute: String?,
    val active: Boolean,
    val order: Int,
    val inherited: Boolean,
    val updatedAt: Instant,
    val updatedBy: String,
) {
    constructor(r: ValidationRule, inherited: Boolean) : this(
        r.id.value,
        r.typeId.value,
        r.name,
        r.condition,
        r.message,
        r.attribute,
        r.active,
        r.order,
        inherited,
        r.updatedAt,
        r.updatedBy,
    )
}
