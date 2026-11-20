package com.plm.extensions.domain

import com.plm.access.api.Permission
import com.plm.extensions.api.ActionDefinition
import com.plm.extensions.api.ActionHandler
import com.plm.extensions.api.ActionPlacement
import com.plm.extensions.infra.ExtensionRepository
import com.plm.platform.actor.Actor
import com.plm.platform.contract.PlatformContract
import com.plm.platform.id.TenantId
import com.plm.versioning.api.VersionedObject
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val PLACEHOLDER = Regex("\\$\\{([a-zA-Z.]+)}")

/** The actions of a tenant's active extensions, as registry entries. */
@Component
internal class ExtensionActions(
    private val extensions: ExtensionRepository,
) {
    fun of(tenant: TenantId): List<ActionDefinition> =
        extensions
            .all(tenant)
            .filter { it.enabled && Versions.Range.parse(it.manifest.platform).includes(PlatformContract.VERSION) }
            .flatMap { row ->
                val m = row.manifest
                m.actions.map { a ->
                    ActionDefinition(
                        id = "${m.id}.${a.id}",
                        label = a.label,
                        description = a.description ?: "",
                        icon = a.icon,
                        placement =
                            if (a.placement ==
                                "object.toolbar"
                            ) {
                                ActionPlacement.OBJECT_TOOLBAR
                            } else {
                                ActionPlacement.OBJECT_MORE
                            },
                        order = a.order,
                        group = a.group ?: m.id,
                        types = a.appliesTo.types,
                        states = a.appliesTo.states,
                        requires = a.requires?.let(Permission::valueOf),
                        visibleWhen = a.visibleWhen,
                        handler = ActionHandler.URL,
                        // Settings are filled in now; the object's details when it's shown.
                        url = a.handler.url?.let { url -> fillConfig(url, row.config) },
                        source = m.id,
                    )
                }
            }

    private fun fillConfig(
        url: String,
        config: Map<String, Any?>,
    ) = PLACEHOLDER.replace(url) { m ->
        val name = m.groupValues[1]
        if (name.startsWith("config.")) config[name.removePrefix("config.")]?.toString().orEmpty() else m.value
    }

    /** The object's placeholders, URL-encoded. */
    fun fill(
        url: String,
        obj: VersionedObject,
        model: Map<String, Any?>,
        actor: Actor,
    ): String {
        @Suppress("UNCHECKED_CAST")
        val type = (model["object"] as? Map<String, Any?>)?.get("type")?.toString()
        val values =
            mapOf(
                "object.number" to obj.number,
                "object.id" to obj.id.value.toString(),
                "object.name" to obj.name,
                "object.type" to type,
                "object.state" to obj.latest.state,
                "object.version" to obj.latest.version,
                "user.id" to actor.principal.value,
            )
        return PLACEHOLDER.replace(url) { m ->
            values[m.groupValues[1]]?.let { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") } ?: ""
        }
    }
}
