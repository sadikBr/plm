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
    /** Rules that refuse operations (check-in, set state...) when their condition holds. */
    val rules: List<ManifestRule> = emptyList(),
    /** Events it wants delivered to its service. */
    val subscriptions: List<ManifestSubscription> = emptyList(),
    /** Configuration it needs (types, attributes, value lists, rules...), imported at install and upgrade. */
    val seed: ConfigBundle? = null,
    /** Panels and tabs it adds to object pages (roadmap 5.15), shown in sandboxed frames. */
    val ui: List<ManifestUi> = emptyList(),
    /** Endpoints of its own service the platform serves at `/api/v1/ext/<id>/<path>` (roadmap 5.15). */
    val endpoints: List<ManifestEndpoint> = emptyList(),
)

/**
 * A panel (`object.panel`, on the Details tab) or a tab (`object.tab`) of object pages, loaded from
 * [url] in a sandboxed frame. The page talks to the platform through `postMessage` (see the
 * extensions README): it is sent the object, the user, the language and the theme, and may ask to
 * resize, open a page, refresh, or call the extension's own endpoints.
 */
data class ManifestUi(
    val id: String,
    val slot: String,
    val title: String,
    /** `https://...`; `${config.<key>}` and the object placeholders are filled in, as for actions. */
    val url: String,
    val appliesTo: AppliesTo = AppliesTo(),
    /** The frame's starting height, in pixels; the page may resize it. */
    val height: Int = 240,
    val order: Int = 500,
)

/**
 * An endpoint of the extension's service, served by the platform at `/api/v1/ext/<id>/<path>`
 * (and below it) for signed-in users. The call is forwarded to [url] (plus what follows [path]),
 * signed like webhooks with the [secret] setting, with `X-PLM-User` and `X-PLM-Tenant`.
 */
data class ManifestEndpoint(
    val id: String,
    val path: String,
    val url: String,
    val secret: String,
    val methods: List<String> = listOf("GET"),
    val timeoutSeconds: Int = 10,
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
    /** Questions asked before the action runs (a JSON Schema subset); the answers go to the handler. */
    val form: ActionForm? = null,
    val handler: ManifestHandler,
)

/** A parameter form: `properties` by name, in order, and which are `required`. */
data class ActionForm(
    val title: String? = null,
    val properties: Map<String, FormField> = emptyMap(),
    val required: List<String> = emptyList(),
)

data class FormField(
    /** `string`, `integer`, `number` or `boolean`. */
    val type: String = "string",
    val title: String? = null,
    val description: String? = null,
    /** Allowed values (a choice list), for `string`. */
    val enum: List<String> = emptyList(),
    val default: Any? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val maxLength: Int? = null,
)

/**
 * A call to the extension's own service. The request is a POST of JSON (the object, the user, the
 * form's answers), signed: `X-PLM-Signature: sha256=<hex HMAC-SHA256 of "<X-PLM-Timestamp>.<body>">`
 * keyed with the setting named by [secret]. The answer may carry a message, refresh, navigate or
 * download (see [ActionResult]).
 */
data class WebhookHandler(
    /** `${config.<key>}` and the object placeholders are filled in, as for `url`. */
    val url: String,
    /** The `secret` setting the request is signed with. */
    val secret: String,
    /** Up to 30 s; up to 3600 s for [background] actions. */
    val timeoutSeconds: Int = 10,
    /**
     * Long-running (roadmap 3.11): the action runs as a background job the person can follow, and
     * they're notified when it ends. The call carries `job.progressUrl` and `job.completeUrl` with a
     * `job.token` (header `X-PLM-Job-Token`): the service may report progress while it works, and
     * may answer 202 at once and post its final answer to `completeUrl` later.
     */
    val background: Boolean = false,
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
    /** Calls the extension's service (needs the `webhooks:call` permission). */
    val webhook: WebhookHandler? = null,
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
    val rulesAdded: List<String> = emptyList(),
    val rulesChanged: List<String> = emptyList(),
    val rulesRemoved: List<String> = emptyList(),
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
        "rules:refuse" to "Refuse operations (check-in, set state, revise...) that don't meet its rules",
        "events:subscribe" to "Be told about events (object created, state changed...)",
        "webhooks:call" to "Call its own web services",
        "ui:panels" to "Add panels and tabs to object pages, shown in sandboxed frames",
        "endpoints:serve" to "Serve endpoints of its own service through the platform, to signed-in users",
        "api:read" to "Read through the PLM API as the person running its actions (a token valid 15 minutes)",
        "api:write" to "Make changes through the PLM API as the person running its actions (a token valid 15 minutes)",
        "api:account" to
            "Have a service account of its own, for calls no person starts (tokens issued by an administrator)",
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
