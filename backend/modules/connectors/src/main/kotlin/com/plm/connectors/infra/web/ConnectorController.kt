package com.plm.connectors.infra.web

import com.plm.connectors.api.ConnectorService
import com.plm.connectors.api.SaveManifest
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class ConnectorInfo(
    /** The protocol's version: connectors check it before saving. */
    val protocol: Int = 1,
)

/** The connector protocol (roadmap 8.5), for authoring-tool connectors. See connectors/README.md. */
@RestController
internal class ConnectorController(
    private val connectors: ConnectorService,
) {
    @GetMapping("/api/v1/connectors")
    fun info() = ConnectorInfo()

    @PostMapping("/api/v1/connectors/save")
    fun save(
        actor: Actor,
        @RequestBody manifest: SaveManifest,
    ) = connectors.save(actor, manifest)

    @GetMapping("/api/v1/connectors/models/{id}/files")
    fun files(
        actor: Actor,
        @PathVariable id: UUID,
    ) = connectors.files(actor, id)
}
