package com.plm.variants.infra.web

import com.plm.platform.actor.Actor
import com.plm.variants.api.OptionService
import com.plm.variants.api.SaveChoice
import com.plm.variants.api.SaveOption
import com.plm.variants.api.SaveOptionSet
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateOptionSet(
    val contextId: UUID,
    val name: String,
    val description: String? = null,
)

data class AssignOptionSet(
    /** Null: the part isn't configurable any more. */
    val optionSetId: UUID?,
)

/** Option sets, options and choices (roadmap 7.5), and which parts they configure. */
@RestController
internal class OptionsController(
    private val options: OptionService,
) {
    @GetMapping("/api/v1/option-sets")
    fun sets(
        actor: Actor,
        @RequestParam(required = false) contextId: UUID?,
    ) = options.sets(actor, contextId)

    @PostMapping("/api/v1/option-sets")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestBody request: CreateOptionSet,
    ) = options.create(actor, request.contextId, SaveOptionSet(request.name, request.description))

    @GetMapping("/api/v1/option-sets/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = options.get(actor, id)

    @PutMapping("/api/v1/option-sets/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveOptionSet,
    ) = options.update(actor, id, request)

    @DeleteMapping("/api/v1/option-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = options.delete(actor, id)

    @PostMapping("/api/v1/option-sets/{id}/options")
    fun addOption(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveOption,
    ) = options.addOption(actor, id, request)

    @PutMapping("/api/v1/options/{optionId}")
    fun updateOption(
        actor: Actor,
        @PathVariable optionId: UUID,
        @RequestBody request: SaveOption,
    ) = options.updateOption(actor, optionId, request)

    @DeleteMapping("/api/v1/options/{optionId}")
    fun removeOption(
        actor: Actor,
        @PathVariable optionId: UUID,
    ) = options.removeOption(actor, optionId)

    @PostMapping("/api/v1/options/{optionId}/choices")
    fun addChoice(
        actor: Actor,
        @PathVariable optionId: UUID,
        @RequestBody request: SaveChoice,
    ) = options.addChoice(actor, optionId, request)

    @PutMapping("/api/v1/choices/{choiceId}")
    fun updateChoice(
        actor: Actor,
        @PathVariable choiceId: UUID,
        @RequestBody request: SaveChoice,
    ) = options.updateChoice(actor, choiceId, request)

    @GetMapping("/api/v1/objects/{id}/options")
    fun partOptions(
        actor: Actor,
        @PathVariable id: UUID,
    ) = options.partOptions(actor, id)

    @PutMapping("/api/v1/objects/{id}/options")
    fun assign(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: AssignOptionSet,
    ) = options.assign(actor, id, request.optionSetId)
}

data class CheckSelection(
    /** Option code to choice code, e.g. MOTOR to HT. */
    val selection: Map<String, String>,
)

/** Option rules and selection checks (roadmap 7.6). */
@RestController
internal class OptionRulesController(
    private val rules: com.plm.variants.api.OptionRuleService,
) {
    @GetMapping("/api/v1/option-sets/{id}/rules")
    fun rules(
        actor: Actor,
        @PathVariable id: UUID,
    ) = rules.rules(actor, id)

    @PostMapping("/api/v1/option-sets/{id}/rules")
    fun add(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: com.plm.variants.api.SaveRule,
    ) = rules.addRule(actor, id, request)

    @DeleteMapping("/api/v1/option-rules/{ruleId}")
    fun remove(
        actor: Actor,
        @PathVariable ruleId: UUID,
    ) = rules.removeRule(actor, ruleId)

    @PostMapping("/api/v1/option-sets/{id}/check")
    fun check(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: CheckSelection,
    ) = rules.check(actor, id, request.selection)
}

data class SetLineCondition(
    /** Blank or null: the line always applies. */
    val expression: String?,
)

/** 150% structures (roadmap 7.7): conditions on lines, and the structure for a selection. */
@RestController
internal class ConditionsController(
    private val conditions: com.plm.variants.api.ConditionService,
) {
    @GetMapping("/api/v1/objects/{id}/line-conditions")
    fun conditions(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = conditions.conditions(actor, id, workingCopy)

    @PostMapping("/api/v1/line-conditions")
    fun forLines(
        actor: Actor,
        @RequestBody lineIds: List<UUID>,
    ) = conditions.forLines(actor, lineIds)

    @PutMapping("/api/v1/objects/{id}/usages/{lineId}/condition")
    fun set(
        actor: Actor,
        @PathVariable id: UUID,
        @PathVariable lineId: UUID,
        @RequestBody request: SetLineCondition,
    ) = conditions.setCondition(actor, id, lineId, request.expression)

    /** The 150% structure with what applies to `selection` (option code to choice code). */
    @PostMapping("/api/v1/objects/{id}/configured-structure")
    fun configured(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: CheckSelection,
        @RequestParam(defaultValue = "LATEST") spec: com.plm.bom.api.ConfigSpecType,
        @RequestParam(required = false) state: String?,
    ) = conditions.configured(
        actor,
        id,
        request.selection,
        com.plm.bom.api
            .ConfigSpec(spec, state),
    )
}

/** Variant specifications (roadmap 7.8). */
@RestController
internal class VariantSpecController(
    private val specs: com.plm.variants.api.VariantSpecService,
) {
    @GetMapping("/api/v1/objects/{id}/variant-specs")
    fun list(
        actor: Actor,
        @PathVariable id: UUID,
    ) = specs.list(actor, id)

    @PostMapping("/api/v1/objects/{id}/variant-specs")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: com.plm.variants.api.SaveVariantSpec,
    ) = specs.create(actor, id, request)

    @GetMapping("/api/v1/variant-specs/{specId}")
    fun get(
        actor: Actor,
        @PathVariable specId: UUID,
    ) = specs.get(actor, specId)

    @PutMapping("/api/v1/variant-specs/{specId}")
    fun update(
        actor: Actor,
        @PathVariable specId: UUID,
        @RequestBody request: com.plm.variants.api.SaveVariantSpec,
    ) = specs.update(actor, specId, request)

    @DeleteMapping("/api/v1/variant-specs/{specId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable specId: UUID,
    ) = specs.delete(actor, specId)

    @GetMapping("/api/v1/variant-specs/{specId}/structure")
    fun structure(
        actor: Actor,
        @PathVariable specId: UUID,
    ) = specs.structure(actor, specId)
}
