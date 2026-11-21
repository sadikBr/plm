package com.plm.change.infra.web

import com.plm.change.api.CreatePromotion
import com.plm.change.api.PromotionService
import com.plm.change.api.PromotionStatus
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Promotion requests (roadmap 5.7). */
@RestController
internal class PromotionController(
    private val promotions: PromotionService,
) {
    @GetMapping("/api/v1/promotion-requests")
    fun list(
        actor: Actor,
        @RequestParam(required = false) status: PromotionStatus?,
    ) = promotions.list(actor, status)

    @PostMapping("/api/v1/promotion-requests")
    fun create(
        actor: Actor,
        @RequestBody request: CreatePromotion,
    ) = promotions.create(actor, request)

    @GetMapping("/api/v1/promotion-requests/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = promotions.get(actor, id)

    @PostMapping("/api/v1/promotion-requests/{id}/cancel")
    fun cancel(
        actor: Actor,
        @PathVariable id: UUID,
    ) = promotions.cancel(actor, id)

    @GetMapping("/api/v1/objects/{id}/promotion-requests")
    fun forObject(
        actor: Actor,
        @PathVariable id: UUID,
    ) = promotions.forObject(actor, id)
}
