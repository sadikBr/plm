package com.plm.extensions.infra.web

import com.plm.extensions.api.EXTENSION_PERMISSIONS
import com.plm.extensions.api.ExtensionPreview
import com.plm.extensions.api.ExtensionService
import com.plm.extensions.api.InstalledExtension
import com.plm.platform.actor.Actor
import com.plm.platform.contract.PlatformContract
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
internal class ExtensionController(
    private val extensions: ExtensionService,
) {
    /** The platform contract version extensions are checked against, and the permissions they may ask for. */
    @GetMapping("/api/v1/platform")
    fun platform() = PlatformResponse(PlatformContract.VERSION, EXTENSION_PERMISSIONS)

    @GetMapping("/api/v1/extensions")
    fun list(actor: Actor) = extensions.list(actor).map(::ExtensionResponse)

    @GetMapping("/api/v1/extensions/{id}")
    fun get(
        actor: Actor,
        @PathVariable id: String,
    ) = ExtensionResponse(extensions.get(actor, id))

    /** What installing (or upgrading to) this manifest would do. Changes nothing. */
    @PostMapping("/api/v1/extensions/preview")
    fun preview(
        actor: Actor,
        @RequestBody request: InstallExtensionRequest,
    ) = PreviewResponse(extensions.preview(actor, request.manifest, request.config.orEmpty()))

    /** Installs, or upgrades to a newer version. */
    @PostMapping("/api/v1/extensions")
    @ResponseStatus(HttpStatus.CREATED)
    fun install(
        actor: Actor,
        @RequestBody request: InstallExtensionRequest,
    ) = ExtensionResponse(extensions.install(actor, request.manifest, request.config.orEmpty()))

    @PutMapping("/api/v1/extensions/{id}/config")
    fun configure(
        actor: Actor,
        @PathVariable id: String,
        @RequestBody request: ConfigureExtensionRequest,
    ) = ExtensionResponse(extensions.configure(actor, id, request.config))

    @PutMapping("/api/v1/extensions/{id}/enabled")
    fun setEnabled(
        actor: Actor,
        @PathVariable id: String,
        @RequestBody request: EnableExtensionRequest,
    ) = ExtensionResponse(extensions.setEnabled(actor, id, request.enabled))

    @DeleteMapping("/api/v1/extensions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun uninstall(
        actor: Actor,
        @PathVariable id: String,
    ) = extensions.uninstall(actor, id)
}

internal data class PlatformResponse(
    val contractVersion: String,
    /** Permission → what it allows. */
    val extensionPermissions: Map<String, String>,
)

internal data class InstallExtensionRequest(
    /** The manifest, as YAML or JSON text. */
    val manifest: String,
    /** Settings by key (secrets included; never returned). */
    val config: Map<String, Any?>? = null,
)

internal data class ConfigureExtensionRequest(
    val config: Map<String, Any?>,
)

internal data class EnableExtensionRequest(
    val enabled: Boolean,
)

internal data class ExtensionResponse(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val publisher: String?,
    val platform: String,
    val permissions: List<String>,
    val status: String,
    val enabled: Boolean,
    val config: Map<String, Any?>,
    val secretsSet: Set<String>,
    val manifest: com.plm.extensions.api.ExtensionManifest,
    val source: String,
    val installedAt: java.time.Instant,
    val installedBy: String,
    val updatedAt: java.time.Instant,
    val updatedBy: String,
    /** Its audit trail: GET /api/v1/audit?aggregateId=… */
    val auditId: java.util.UUID,
) {
    constructor(e: InstalledExtension) : this(
        e.manifest.id,
        e.manifest.name,
        e.manifest.version,
        e.manifest.description,
        e.manifest.publisher,
        e.manifest.platform,
        e.manifest.permissions,
        e.status.name,
        e.enabled,
        e.config,
        e.secretsSet,
        e.manifest,
        e.source,
        e.installedAt,
        e.installedBy,
        e.updatedAt,
        e.updatedBy,
        com.plm.extensions.api.ExtensionChanged
            .aggregateIdOf(e.manifest.id),
    )
}

internal data class PreviewResponse(
    val manifest: com.plm.extensions.api.ExtensionManifest,
    val mode: String,
    val installedVersion: String?,
    val compatible: Boolean,
    val platformVersion: String,
    val permissionsAdded: List<String>,
    val permissionsRemoved: List<String>,
    val actionsAdded: List<String>,
    val actionsChanged: List<String>,
    val actionsRemoved: List<String>,
    val rulesAdded: List<String>,
    val rulesChanged: List<String>,
    val rulesRemoved: List<String>,
    val seedChanges: List<com.plm.configuration.api.PlannedChange>,
    val configProblems: List<String>,
    val canInstall: Boolean,
) {
    constructor(p: ExtensionPreview) : this(
        p.manifest,
        p.mode.name,
        p.installedVersion,
        p.compatible,
        p.platformVersion,
        p.permissionsAdded,
        p.permissionsRemoved,
        p.actionsAdded,
        p.actionsChanged,
        p.actionsRemoved,
        p.rulesAdded,
        p.rulesChanged,
        p.rulesRemoved,
        p.seedChanges,
        p.configProblems,
        p.canInstall,
    )
}
