package com.plm.configuration.infra.web

import com.plm.configuration.api.ConfigBundle
import com.plm.configuration.api.ConfigurationService
import com.plm.configuration.api.ImportPlan
import com.plm.platform.actor.Actor
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/configuration")
internal class ConfigurationController(
    private val configuration: ConfigurationService,
) {
    /** The whole configuration as one bundle, ready to commit to Git. Site admins only. */
    @GetMapping("/export")
    fun export(actor: Actor): ResponseEntity<ConfigBundle> =
        ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"plm-config.json\"")
            .body(configuration.export(actor))

    /**
     * Compares a bundle with this environment and, unless `dryRun` (the default) or there are
     * conflicts, applies it in one transaction. Adds and updates; never deletes. Site admins only.
     */
    @PostMapping("/import")
    fun import(
        actor: Actor,
        @RequestBody bundle: ConfigBundle,
        @RequestParam(defaultValue = "true") dryRun: Boolean,
    ): ImportPlanResponse = ImportPlanResponse(configuration.import(actor, bundle, dryRun))
}

internal data class ImportPlanResponse(
    val applied: Boolean,
    val creates: Int,
    val updates: Int,
    val unchanged: Int,
    val conflicts: Int,
    val changes: List<com.plm.configuration.api.PlannedChange>,
) {
    constructor(p: ImportPlan) : this(
        p.applied,
        p.count(com.plm.configuration.api.ChangeAction.CREATE),
        p.count(com.plm.configuration.api.ChangeAction.UPDATE),
        p.count(com.plm.configuration.api.ChangeAction.UNCHANGED),
        p.count(com.plm.configuration.api.ChangeAction.CONFLICT),
        p.changes,
    )
}
