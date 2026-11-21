package com.plm.extensions.infra.web

import com.plm.extensions.api.ScriptSettings
import com.plm.extensions.api.ScriptTest
import com.plm.extensions.api.ServerScriptService
import com.plm.platform.actor.Actor
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

/** Sandboxed server scripts (roadmap 5.14). */
@RestController
internal class ServerScriptController(
    private val scripts: ServerScriptService,
) {
    @GetMapping("/api/v1/server-scripts")
    fun list(actor: Actor) = scripts.list(actor)

    @PostMapping("/api/v1/server-scripts")
    @ResponseStatus(HttpStatus.CREATED)
    fun define(
        actor: Actor,
        @RequestBody settings: ScriptSettings,
    ) = scripts.define(actor, settings)

    @PutMapping("/api/v1/server-scripts/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody settings: ScriptSettings,
    ) = scripts.update(actor, id, settings)

    @DeleteMapping("/api/v1/server-scripts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = scripts.delete(actor, id)

    @GetMapping("/api/v1/server-scripts/{id}/runs")
    fun runs(
        actor: Actor,
        @PathVariable id: UUID,
    ) = scripts.runs(actor, id)

    /** Runs a saved script on sample input; nothing is recorded. */
    @PostMapping("/api/v1/server-scripts/{id}/test")
    fun test(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody test: ScriptTest,
    ) = scripts.test(actor, id, test)

    /** Runs an unsaved script on sample input. */
    @PostMapping("/api/v1/server-scripts/test")
    fun testDraft(
        actor: Actor,
        @RequestBody test: ScriptTest,
    ) = scripts.test(actor, null, test)
}
