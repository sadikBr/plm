package com.plm.audit.infra.web

import com.plm.audit.api.AuditConfigService
import com.plm.audit.api.SaveAuditConfig
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Audit configuration (roadmap 9.9). */
@RestController
internal class AuditConfigController(
    private val config: AuditConfigService,
) {
    @GetMapping("/api/v1/audit/config")
    fun get(actor: Actor) = config.config(actor)

    @PutMapping("/api/v1/audit/config")
    fun update(
        actor: Actor,
        @RequestBody command: SaveAuditConfig,
    ) = config.update(actor, command)

    @GetMapping("/api/v1/audit/kinds")
    fun kinds(actor: Actor) = config.kinds(actor)

    @PostMapping("/api/v1/audit/purge")
    fun purge(actor: Actor) = mapOf("purged" to config.purge(actor))
}
