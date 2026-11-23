package com.plm.migration.infra.web

import com.plm.migration.api.MigrationMappings
import com.plm.migration.api.MigrationService
import com.plm.platform.actor.Actor
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/** Migrations from other PLM systems (roadmap 9.3), for site administrators. */
@RestController
internal class MigrationController(
    private val migrations: MigrationService,
) {
    @GetMapping("/api/v1/migrations")
    fun list(actor: Actor) = migrations.list(actor)

    @PostMapping("/api/v1/migrations", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: Actor,
        @RequestParam contextId: UUID,
        @RequestParam(required = false) name: String?,
        @RequestPart("package") pkg: MultipartFile,
    ) = migrations.create(actor, name ?: "", contextId, pkg.originalFilename ?: "package.zip", pkg.bytes)

    @GetMapping("/api/v1/migrations/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = migrations.get(actor, id)

    @PutMapping("/api/v1/migrations/{id}/mappings")
    fun mappings(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody mappings: MigrationMappings,
    ) = migrations.updateMappings(actor, id, mappings)

    @GetMapping("/api/v1/migrations/{id}/items")
    fun items(
        actor: Actor,
        @PathVariable id: UUID,
    ) = migrations.items(actor, id)

    @PostMapping("/api/v1/migrations/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun run(
        actor: Actor,
        @PathVariable id: UUID,
    ) = migrations.run(actor, id)

    @DeleteMapping("/api/v1/migrations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = migrations.delete(actor, id)
}
