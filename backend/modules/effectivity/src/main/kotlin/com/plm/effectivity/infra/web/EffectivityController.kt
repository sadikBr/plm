package com.plm.effectivity.infra.web

import com.plm.effectivity.api.AddEffectivity
import com.plm.effectivity.api.EffectivityService
import com.plm.effectivity.api.SetEffectivity
import com.plm.platform.actor.Actor
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

data class CreateEffectivityContext(
    val endItemId: UUID,
    val description: String? = null,
)

/** Effectivity (roadmap 7.1): end items, and when versions and lines apply. */
@RestController
internal class EffectivityController(
    private val effectivity: EffectivityService,
) {
    @GetMapping("/api/v1/effectivity-contexts")
    fun contexts(actor: Actor) = effectivity.contexts(actor)

    @PostMapping("/api/v1/effectivity-contexts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createContext(
        actor: Actor,
        @RequestBody request: CreateEffectivityContext,
    ) = effectivity.createContext(actor, request.endItemId, request.description)

    @DeleteMapping("/api/v1/effectivity-contexts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteContext(
        actor: Actor,
        @PathVariable id: UUID,
    ) = effectivity.deleteContext(actor, id)

    @GetMapping("/api/v1/objects/{id}/effectivity")
    fun forPart(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") workingCopy: Boolean,
    ) = effectivity.forPart(actor, id, workingCopy)

    /** The effectivity a change notice sets on its results (roadmap 7.3). */
    @GetMapping("/api/v1/changes/{id}/effectivity")
    fun forChange(
        actor: Actor,
        @PathVariable id: UUID,
    ) = effectivity.forChange(actor, id)

    @PostMapping("/api/v1/objects/{id}/effectivity")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: AddEffectivity,
    ) = effectivity.add(actor, id, request)

    @PutMapping("/api/v1/effectivity/{effectivityId}")
    fun update(
        actor: Actor,
        @PathVariable effectivityId: UUID,
        @RequestBody request: SetEffectivity,
    ) = effectivity.update(actor, effectivityId, request)

    @DeleteMapping("/api/v1/effectivity/{effectivityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable effectivityId: UUID,
    ) = effectivity.delete(actor, effectivityId)
}
