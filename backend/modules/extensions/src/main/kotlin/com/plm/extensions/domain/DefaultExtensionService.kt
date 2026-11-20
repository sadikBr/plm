package com.plm.extensions.domain

import com.plm.configuration.api.ChangeAction
import com.plm.configuration.api.ConfigurationService
import com.plm.extensions.api.ConfigField
import com.plm.extensions.api.ExtensionChanged
import com.plm.extensions.api.ExtensionManifest
import com.plm.extensions.api.ExtensionPreview
import com.plm.extensions.api.ExtensionService
import com.plm.extensions.api.ExtensionStatus
import com.plm.extensions.api.InstallMode
import com.plm.extensions.api.InstalledExtension
import com.plm.extensions.infra.ExtensionRepository
import com.plm.extensions.infra.ExtensionRow
import com.plm.identity.api.ServiceAccountService
import com.plm.platform.actor.Actor
import com.plm.platform.contract.PlatformContract
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.time.Clock

/**
 * Deliberately not @Transactional: a seed is imported by the configuration module in its own
 * transaction (a dry run rolls back), so this service runs its own writes in [tx] around it.
 */
@Service
internal class DefaultExtensionService(
    private val extensions: ExtensionRepository,
    private val configuration: ConfigurationService,
    private val serviceAccounts: ServiceAccountService,
    private val events: DomainEvents,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : ExtensionService {
    override fun list(actor: Actor): List<InstalledExtension> {
        requireAdmin(actor)
        return extensions.all(actor.tenant).map(::toInstalled)
    }

    override fun get(
        actor: Actor,
        id: String,
    ): InstalledExtension {
        requireAdmin(actor)
        return toInstalled(find(actor, id))
    }

    override fun preview(
        actor: Actor,
        source: String,
        config: Map<String, Any?>,
    ): ExtensionPreview {
        requireAdmin(actor)
        val manifest = Manifests.parse(source)
        val existing = extensions.find(actor.tenant, manifest.id)
        if (existing != null && Versions.compare(manifest.version, existing.manifest.version) <= 0) {
            throw ConflictException(
                "EXTENSION_VERSION_NOT_NEWER",
                "${manifest.name} ${existing.manifest.version} is installed; an upgrade needs a newer version than ${manifest.version}",
                mapOf("installed" to existing.manifest.version, "offered" to manifest.version),
            )
        }
        val before = existing?.manifest
        val oldActions = before?.actions.orEmpty().associateBy { it.id }
        val newActions = manifest.actions.associateBy { it.id }
        val oldRules = before?.rules.orEmpty().associateBy { it.id }
        val newRules = manifest.rules.associateBy { it.id }
        val seedChanges =
            manifest.seed
                ?.let { configuration.import(actor, it, dryRun = true).changes }
                .orEmpty()
                .filter { it.action != ChangeAction.UNCHANGED }
        val (_, _, problems) = settings(manifest, config, existing)
        return ExtensionPreview(
            manifest = manifest,
            mode = if (existing == null) InstallMode.INSTALL else InstallMode.UPGRADE,
            installedVersion = before?.version,
            compatible = compatible(manifest),
            platformVersion = PlatformContract.VERSION,
            permissionsAdded = manifest.permissions - before?.permissions.orEmpty().toSet(),
            permissionsRemoved = before?.permissions.orEmpty() - manifest.permissions.toSet(),
            actionsAdded = (newActions.keys - oldActions.keys).toList(),
            actionsChanged = newActions.keys.filter { it in oldActions && oldActions[it] != newActions[it] },
            actionsRemoved = (oldActions.keys - newActions.keys).toList(),
            rulesAdded = (newRules.keys - oldRules.keys).toList(),
            rulesChanged = newRules.keys.filter { it in oldRules && oldRules[it] != newRules[it] },
            rulesRemoved = (oldRules.keys - newRules.keys).toList(),
            seedChanges = seedChanges,
            configProblems = problems,
        )
    }

    override fun install(
        actor: Actor,
        source: String,
        config: Map<String, Any?>,
    ): InstalledExtension {
        val preview = preview(actor, source, config)
        val manifest = preview.manifest
        if (!preview.compatible) {
            throw ConflictException(
                "EXTENSION_INCOMPATIBLE",
                "${manifest.name} supports platform ${manifest.platform}; this platform is ${PlatformContract.VERSION}",
                mapOf("supports" to manifest.platform, "platform" to PlatformContract.VERSION),
            )
        }
        if (preview.configProblems.isNotEmpty()) {
            throw ValidationException(
                "INVALID_EXTENSION_CONFIG",
                preview.configProblems.joinToString("; "),
                mapOf("errors" to preview.configProblems),
            )
        }
        manifest.seed?.let {
            val plan = configuration.import(actor, it, dryRun = false)
            if (!plan.applied) {
                throw ConflictException(
                    "EXTENSION_SEED_CONFLICT",
                    "The extension's configuration conflicts with this site's",
                    mapOf(
                        "conflicts" to
                            plan.changes
                                .filter { c ->
                                    c.action == ChangeAction.CONFLICT
                                }.map { c ->
                                    "${c.kind} ${c.key}: ${c.details.joinToString()}"
                                },
                    ),
                )
            }
        }
        return tx.execute {
            val existing = extensions.find(actor.tenant, manifest.id)
            val (values, secrets) = settings(manifest, config, existing)
            val now = clock.instant()
            val row =
                ExtensionRow(
                    manifest = manifest,
                    source = source,
                    config = values,
                    secrets = secrets,
                    enabled = existing?.enabled ?: true,
                    installedAt = existing?.installedAt ?: now,
                    installedBy = existing?.installedBy ?: actor.principal.value,
                    updatedAt = now,
                    updatedBy = actor.principal.value,
                )
            extensions.save(actor.tenant, row)
            if ("api:account" in manifest.permissions) {
                serviceAccounts.forExtension(actor, manifest.id, manifest.name)
                serviceAccounts.setExtensionActive(actor, manifest.id, row.enabled)
            } else {
                serviceAccounts.setExtensionActive(actor, manifest.id, false)
            }
            events.publish(
                actor,
                ExtensionChanged(
                    manifest.id,
                    if (existing == null) "INSTALLED" else "UPGRADED",
                    manifest.version,
                    existing?.manifest?.version,
                    manifest.permissions,
                ),
            )
            toInstalled(row)
        }
    }

    override fun configure(
        actor: Actor,
        id: String,
        config: Map<String, Any?>,
    ): InstalledExtension {
        requireAdmin(actor)
        return tx.execute {
            val existing = find(actor, id)
            val (values, secrets, problems) = settings(existing.manifest, config, existing)
            if (problems.isNotEmpty()) {
                throw ValidationException(
                    "INVALID_EXTENSION_CONFIG",
                    problems.joinToString("; "),
                    mapOf(
                        "errors" to problems,
                    ),
                )
            }
            val row =
                existing.copy(
                    config = values,
                    secrets = secrets,
                    updatedAt = clock.instant(),
                    updatedBy = actor.principal.value,
                )
            extensions.save(actor.tenant, row)
            events.publish(actor, ExtensionChanged(id, "CONFIGURED", row.manifest.version))
            toInstalled(row)
        }
    }

    override fun setEnabled(
        actor: Actor,
        id: String,
        enabled: Boolean,
    ): InstalledExtension {
        requireAdmin(actor)
        return tx.execute {
            val existing = find(actor, id)
            val row = existing.copy(enabled = enabled, updatedAt = clock.instant(), updatedBy = actor.principal.value)
            extensions.save(actor.tenant, row)
            serviceAccounts.setExtensionActive(actor, id, enabled && "api:account" in row.manifest.permissions)
            events.publish(actor, ExtensionChanged(id, if (enabled) "ENABLED" else "DISABLED", row.manifest.version))
            toInstalled(row)
        }
    }

    override fun uninstall(
        actor: Actor,
        id: String,
    ) {
        requireAdmin(actor)
        tx.executeWithoutResult {
            val existing = find(actor, id)
            extensions.delete(actor.tenant, id)
            // Its account stays (the audit trail names it) but can't sign in any more.
            serviceAccounts.setExtensionActive(actor, id, false)
            events.publish(actor, ExtensionChanged(id, "UNINSTALLED", existing.manifest.version))
        }
    }

    /**
     * The settings to store: [given] values over the existing ones over the manifest's defaults,
     * secrets apart. Returns (values, secrets, problems).
     */
    private fun settings(
        manifest: ExtensionManifest,
        given: Map<String, Any?>,
        existing: ExtensionRow?,
    ): Triple<Map<String, Any?>, Map<String, String>, List<String>> {
        val problems = mutableListOf<String>()
        given.keys.filter { it !in manifest.config }.forEach { problems += "Unknown setting '$it'" }
        val values = linkedMapOf<String, Any?>()
        val secrets = linkedMapOf<String, String>()
        for ((key, field) in manifest.config) {
            val label = field.label ?: key
            if (field.type == "secret") {
                val value = (given[key] as? String)?.takeIf { it.isNotEmpty() } ?: existing?.secrets?.get(key)
                if (value != null) {
                    secrets[key] = value
                } else if (field.required) {
                    problems += "$label is required"
                }
                continue
            }
            val raw = if (given.containsKey(key)) given[key] else existing?.config?.get(key) ?: field.default
            val value = raw?.takeUnless { it is String && it.isBlank() }
            if (value == null) {
                if (field.required) problems += "$label is required"
                continue
            }
            check(field, label, value)?.let { problems += it } ?: run { values[key] = normalize(field, value) }
        }
        return Triple(values, secrets, problems)
    }

    private fun check(
        field: ConfigField,
        label: String,
        value: Any,
    ): String? =
        when (field.type) {
            "number" ->
                if (value is Number ||
                    value.toString().toBigDecimalOrNull() != null
                ) {
                    null
                } else {
                    "$label must be a number"
                }
            "boolean" ->
                if (value is Boolean ||
                    value.toString() in setOf("true", "false")
                ) {
                    null
                } else {
                    "$label must be true or false"
                }
            "choice" ->
                if (value.toString() in
                    field.options
                ) {
                    null
                } else {
                    "$label must be one of ${field.options.joinToString()}"
                }
            "url" -> {
                val uri = runCatching { URI(value.toString()) }.getOrNull()
                if (uri != null &&
                    uri.scheme in setOf("https", "http") &&
                    uri.host != null
                ) {
                    null
                } else {
                    "$label must be a web address (https://...)"
                }
            }
            else -> if (value.toString().length > 2000) "$label is too long" else null
        }

    private fun normalize(
        field: ConfigField,
        value: Any,
    ): Any =
        when (field.type) {
            "number" -> value.toString().toBigDecimal()
            "boolean" -> value.toString().toBoolean()
            else -> value.toString()
        }

    private fun compatible(manifest: ExtensionManifest) =
        Versions.Range.parse(manifest.platform).includes(PlatformContract.VERSION)

    private fun toInstalled(row: ExtensionRow) =
        InstalledExtension(
            // The stored JSON sorts keys; the source has the settings in the author's order.
            manifest = Manifests.read(row.source) ?: row.manifest,
            source = row.source,
            config = row.config,
            secretsSet = row.secrets.keys,
            enabled = row.enabled,
            status =
                when {
                    !compatible(row.manifest) -> ExtensionStatus.INCOMPATIBLE
                    !row.enabled -> ExtensionStatus.DISABLED
                    else -> ExtensionStatus.ACTIVE
                },
            installedAt = row.installedAt,
            installedBy = row.installedBy,
            updatedAt = row.updatedAt,
            updatedBy = row.updatedBy,
        )

    private fun find(
        actor: Actor,
        id: String,
    ) = extensions.find(actor.tenant, id)
        ?: throw NotFoundException("EXTENSION_NOT_FOUND", "No extension $id is installed", mapOf("extensionId" to id))

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "Only site administrators manage extensions")
    }
}
