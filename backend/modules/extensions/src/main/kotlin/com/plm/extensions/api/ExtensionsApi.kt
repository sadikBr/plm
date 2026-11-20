package com.plm.extensions.api

import com.plm.configuration.api.ConfigBundle
import com.plm.configuration.api.PlannedChange
import com.plm.platform.actor.Actor
import com.plm.platform.events.DomainEvent
import java.time.Instant
import java.util.UUID

/**
 * An extension package's manifest (`plm-extension.yaml`). See docs/EXTENSIBILITY.md for the
 * format, and `extensions/samples` for examples.
 */
data class ExtensionManifest(
    /** Reverse-domain style, e.g. `acme.erp-sync`. Never changes between versions. */
    val id: String,
    val name: String,
    /** Semantic version, e.g. `1.3.0`. Upgrades must increase it. */
    val version: String,
    val description: String? = null,
    val publisher: String? = null,
    /** Platform contract versions it supports, e.g. `>=1.0 <2.0`. */
    val platform: String,
    /** What it may do; shown to the administrator before installing. */
    val permissions: List<String> = emptyList(),
    /** Settings the administrator fills in at install, by key. */
    val config: Map<String, ConfigField> = emptyMap(),
    val actions: List<ManifestAction> = emptyList(),
    /** Configuration it needs (types, attributes, value lists, rules...), imported at install and upgrade. */
    val seed: ConfigBundle? = null,
)

data class ConfigField(
    /** `string`, `url`, `secret`, `number`, `boolean` or `choice`. */
    val type: String = "string",
    val label: String? = null,
    val description: String? = null,
    val required: Boolean = false,
    val default: Any? = null,
    /** For `choice`. */
    val options: List<String> = emptyList(),
)

data class ManifestAction(
    /** Unique within the extension; the registry id is `<extension id>.<action id>`. */
    val id: String,
    val label: String,
    val description: String? = null,
    val icon: String? = null,
    /** `object.toolbar` or `object.more`. */
    val placement: String = "object.more",
    val order: Int = 500,
    val group: String? = null,
    val appliesTo: AppliesTo = AppliesTo(),
    /** Permission needed on the object: READ, MODIFY, REVISE, PROMOTE, MANAGE... */
    val requires: String? = null,
    /** A CEL condition; the action shows only where it's true. */
    val visibleWhen: String? = null,
    val handler: ManifestHandler,
)

data class AppliesTo(
    /** Type names; subtypes included. Empty: every type. */
    val types: List<String> = emptyList(),
    /** State keys. Empty: every state. */
    val states: List<String> = emptyList(),
)

/** What the action does. Webhooks and workflows come with roadmap 2.4. */
data class ManifestHandler(
    /**
     * Opens this URL in a new tab. `${config.<key>}` (not secrets), `${object.number}`, `${object.id}`,
     * `${object.name}`, `${object.type}`, `${object.state}`, `${object.version}` and `${user.id}` are
     * replaced (URL-encoded).
     */
    val url: String? = null,
)

enum class ExtensionStatus {
    ACTIVE,
    DISABLED,

    /** Its platform range doesn't include this platform's contract version: switched off. */
    INCOMPATIBLE,
}

data class InstalledExtension(
    val manifest: ExtensionManifest,
    val source: String,
    /** Non-secret settings. Secret ones are only reported as set or not. */
    val config: Map<String, Any?>,
    val secretsSet: Set<String>,
    val enabled: Boolean,
    val status: ExtensionStatus,
    val installedAt: Instant,
    val installedBy: String,
    val updatedAt: Instant,
    val updatedBy: String,
)

enum class InstallMode { INSTALL, UPGRADE }

/** What installing a manifest would do, before doing it. */
data class ExtensionPreview(
    val manifest: ExtensionManifest,
    val mode: InstallMode,
    val installedVersion: String?,
    val compatible: Boolean,
    val platformVersion: String,
    val permissionsAdded: List<String>,
    val permissionsRemoved: List<String>,
    val actionsAdded: List<String>,
    val actionsChanged: List<String>,
    val actionsRemoved: List<String>,
    /** The seed's configuration changes (a dry run of the import). */
    val seedChanges: List<PlannedChange>,
    /** Settings that are missing or invalid; installing is refused until they're fixed. */
    val configProblems: List<String>,
) {
    val canInstall: Boolean
        get() = compatible && configProblems.isEmpty() && seedChanges.none { it.action.name == "CONFLICT" }
}

/** Every permission an extension may ask for, with what it means. */
val EXTENSION_PERMISSIONS: Map<String, String> =
    linkedMapOf(
        "ui:actions" to "Add actions to toolbars and menus",
        "config:seed" to
            "Add or change configuration (types, attributes, value lists, rules) when installed or upgraded",
        "objects:read" to "Read the objects its actions are used on",
        "objects:set-state" to "Change the lifecycle state of objects",
        "events:subscribe" to "Be told about events (object created, state changed...)",
        "webhooks:call" to "Call its own web services",
    )

/**
 * Extension packages per tenant (roadmap 2.3). Site administrators only (`ADMIN_REQUIRED`).
 *
 * Error codes: `INVALID_MANIFEST`, `EXTENSION_INCOMPATIBLE`, `EXTENSION_VERSION_NOT_NEWER`,
 * `INVALID_EXTENSION_CONFIG`, `EXTENSION_SEED_CONFLICT`, `EXTENSION_NOT_FOUND`.
 */
interface ExtensionService {
    fun list(actor: Actor): List<InstalledExtension>

    fun get(
        actor: Actor,
        id: String,
    ): InstalledExtension

    /** Parses and checks [source] (YAML or JSON) and says what installing it would do. Changes nothing. */
    fun preview(
        actor: Actor,
        source: String,
        config: Map<String, Any?> = emptyMap(),
    ): ExtensionPreview

    /** Installs, or upgrades to a newer version (settings not given are kept). */
    fun install(
        actor: Actor,
        source: String,
        config: Map<String, Any?> = emptyMap(),
    ): InstalledExtension

    /** Changes settings; a secret left out (or null) keeps its value. */
    fun configure(
        actor: Actor,
        id: String,
        config: Map<String, Any?>,
    ): InstalledExtension

    fun setEnabled(
        actor: Actor,
        id: String,
        enabled: Boolean,
    ): InstalledExtension

    /** Removes it and its actions. Configuration its seed added stays (data may use it). */
    fun uninstall(
        actor: Actor,
        id: String,
    )
}

/** Audit trail of extensions: installed, upgraded, configured, enabled, disabled, uninstalled. */
data class ExtensionChanged(
    val extensionId: String,
    /** INSTALLED, UPGRADED, CONFIGURED, ENABLED, DISABLED, UNINSTALLED */
    val change: String,
    val version: String,
    val previousVersion: String? = null,
    val permissions: List<String> = emptyList(),
) : DomainEvent {
    override val eventType = "extensions.ExtensionChanged.v1"
    override val aggregateType = "extension"
    override val aggregateId: UUID = aggregateIdOf(extensionId)

    companion object {
        fun aggregateIdOf(extensionId: String): UUID = UUID.nameUUIDFromBytes("extension:$extensionId".toByteArray())
    }
}
