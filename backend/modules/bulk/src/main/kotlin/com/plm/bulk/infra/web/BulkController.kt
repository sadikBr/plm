package com.plm.bulk.infra.web

import com.plm.bulk.api.BulkRequest
import com.plm.bulk.api.BulkService
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
internal class BulkController(
    private val bulk: BulkService,
) {
    /** Starts a mass operation; follow it as a job (`GET /api/v1/jobs/{id}`). */
    @PostMapping("/api/v1/bulk")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(
        actor: Actor,
        @RequestBody request: BulkRequest,
    ) = bulk.start(actor, request)
}
