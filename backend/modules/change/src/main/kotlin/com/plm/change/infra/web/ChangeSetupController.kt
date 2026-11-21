package com.plm.change.infra.web

import com.plm.change.api.ChangeSetupService
import com.plm.change.api.SaveAssociationRule
import com.plm.change.api.SaveChangeTemplate
import com.plm.change.api.UseChangeTemplate
import com.plm.change.api.VarianceScope
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Change templates, association rules and variances (roadmap 5.10). */
@RestController
internal class ChangeSetupController(
    private val setup: ChangeSetupService,
) {
    @GetMapping("/api/v1/change-templates")
    fun templates(actor: Actor) = setup.templates(actor)

    @PostMapping("/api/v1/change-templates")
    fun create(
        actor: Actor,
        @RequestBody request: SaveChangeTemplate,
    ) = setup.saveTemplate(actor, null, request)

    @PutMapping("/api/v1/change-templates/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveChangeTemplate,
    ) = setup.saveTemplate(actor, id, request)

    @DeleteMapping("/api/v1/change-templates/{id}")
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = setup.deleteTemplate(actor, id)

    /** A new change from the template; returns its id. */
    @PostMapping("/api/v1/change-templates/{id}/use")
    fun use(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: UseChangeTemplate,
    ) = Created(setup.useTemplate(actor, id, request))

    @GetMapping("/api/v1/change-association-rules")
    fun rules(actor: Actor) = setup.rules(actor)

    @PostMapping("/api/v1/change-association-rules")
    fun addRule(
        actor: Actor,
        @RequestBody request: SaveAssociationRule,
    ) = setup.addRule(actor, request)

    @DeleteMapping("/api/v1/change-association-rules/{id}")
    fun deleteRule(
        actor: Actor,
        @PathVariable id: UUID,
    ) = setup.deleteRule(actor, id)

    @GetMapping("/api/v1/changes/{id}/variance")
    fun variance(
        actor: Actor,
        @PathVariable id: UUID,
    ) = setup.variance(actor, id)

    @PutMapping("/api/v1/changes/{id}/variance")
    fun setVariance(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody scope: VarianceScope,
    ) = setup.setVariance(actor, id, scope)

    /** Approved variances in force on a part or document. */
    @GetMapping("/api/v1/objects/{id}/variances")
    fun active(
        actor: Actor,
        @PathVariable id: UUID,
    ) = setup.activeVariances(actor, id)
}
