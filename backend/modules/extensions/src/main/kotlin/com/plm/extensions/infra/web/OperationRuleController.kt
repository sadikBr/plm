package com.plm.extensions.infra.web

import com.plm.extensions.api.OperationRuleService
import com.plm.extensions.api.OperationRuleSettings
import com.plm.platform.actor.Actor
import com.plm.versioning.api.GuardedOperation
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class OperationRuleController(
    private val rules: OperationRuleService,
) {
    /** The site's rules and the active extensions' (`source`), alphabetically. Site administrators. */
    @GetMapping("/api/v1/operation-rules")
    fun list(actor: Actor) = rules.list(actor)

    /** The operations rules can refuse. */
    @GetMapping("/api/v1/operation-rules/operations")
    fun operations() = GuardedOperation.entries.map { it.key }

    @PostMapping("/api/v1/operation-rules")
    @ResponseStatus(HttpStatus.CREATED)
    fun define(
        actor: Actor,
        @RequestBody request: OperationRuleSettings,
    ) = rules.define(actor, request)

    @PutMapping("/api/v1/operation-rules/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: OperationRuleSettings,
    ) = rules.update(actor, id, request)

    @DeleteMapping("/api/v1/operation-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = rules.delete(actor, id)
}
