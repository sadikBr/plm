package com.plm.configuration.api

import com.plm.platform.actor.Actor
import java.math.BigDecimal

/**
 * The whole configuration of a tenant as one document: what admins set up without code. Objects
 * refer to each other by name or key, never by internal id, so a bundle exported from one
 * environment applies to another (dev → test → prod). Lists are sorted and the bundle carries no
 * timestamps, so exporting twice gives the same file and Git diffs show only real changes.
 */
data class ConfigBundle(
    /** `plm-config/1`. Bundles with another format are refused. */
    val format: String = FORMAT,
    val teamRoles: List<TeamRoleConfig> = emptyList(),
    /** Units added by administrators (the out-of-the-box ones exist everywhere). */
    val units: List<UnitConfig> = emptyList(),
    val enumerations: List<EnumerationConfig> = emptyList(),
    val numberingSchemes: List<NumberingSchemeConfig> = emptyList(),
    val lifecycles: List<LifecycleConfig> = emptyList(),
    /** Attributes defined once and used by several types (see [TypeConfig.globalAttributes]). */
    val globalAttributes: List<AttributeConfig> = emptyList(),
    /** Parents before children. Includes the out-of-the-box base types, matched by name. */
    val types: List<TypeConfig> = emptyList(),
    /** Site-wide templates for new organizations, products, libraries and projects (organizations' own templates stay with them). */
    val contextTemplates: List<ContextTemplateConfig> = emptyList(),
) {
    companion object {
        const val FORMAT = "plm-config/1"
    }
}

data class ContextTemplateConfig(
    val name: String,
    /** ORGANIZATION, PRODUCT, LIBRARY or PROJECT. */
    val kind: String,
    val description: String? = null,
    val content: com.plm.access.api.TemplateContent =
        com.plm.access.api
            .TemplateContent(),
)

data class UnitConfig(
    val code: String,
    val dimension: String,
    val labels: Map<String, String>,
    val factor: BigDecimal,
    val offset: BigDecimal = BigDecimal.ZERO,
)

data class TeamRoleConfig(
    val key: String,
    val labels: Map<String, String>,
    val description: String? = null,
    val active: Boolean = true,
    val sortOrder: Int = 0,
)

data class EnumerationConfig(
    val name: String,
    val labels: Map<String, String>,
    val values: List<EnumerationValueConfig>,
)

data class EnumerationValueConfig(
    val key: String,
    val labels: Map<String, String>,
    val active: Boolean = true,
)

/** Counters are not part of the configuration: a scheme created by an import starts at 1. */
data class NumberingSchemeConfig(
    val name: String,
    val prefix: String = "",
    val padding: Int = 6,
    val suffix: String = "",
)

data class LifecycleConfig(
    val name: String,
    val labels: Map<String, String>,
    val initialState: String,
    val states: List<LifecycleStateConfig>,
    val transitions: List<LifecycleTransitionConfig>,
)

data class LifecycleStateConfig(
    val key: String,
    val labels: Map<String, String>,
    val editable: Boolean = false,
    val revisable: Boolean = false,
    val requiredAttributes: List<String> = emptyList(),
    val revisionScheme: String? = null,
)

data class LifecycleTransitionConfig(
    val from: String,
    val to: String,
)

data class TypeConfig(
    val name: String,
    val displayName: String,
    /** Parent type name; null for base types. */
    val parent: String? = null,
    val baseClass: String,
    val description: String? = null,
    /** Translations of the display name. */
    val labels: Map<String, String> = emptyMap(),
    val icon: String? = null,
    val instantiable: Boolean = true,
    /** Names of the global attributes this type uses itself. */
    val globalAttributes: List<String> = emptyList(),
    /** Attributes defined on this type (inherited ones are on its ancestors). */
    val attributes: List<AttributeConfig> = emptyList(),
    /** This type's own rule overrides; null: none (the type inherits all its rules). */
    val rules: TypeRulesConfig? = null,
    /** Name of the lifecycle assigned to this type itself; null: inherited. */
    val lifecycle: String? = null,
    /** This type's own layouts. */
    val layouts: List<LayoutConfig> = emptyList(),
    /** This type's own validation rules (its ancestors' are on them). */
    val validationRules: List<ValidationRuleConfig> = emptyList(),
)

/** A validation rule, identified by its name within its type. */
data class ValidationRuleConfig(
    val name: String,
    val condition: String,
    val message: String,
    val attribute: String? = null,
    val active: Boolean = true,
    val order: Int = 0,
)

data class AttributeConfig(
    val name: String,
    val labels: Map<String, String>,
    val description: String? = null,
    val dataType: String,
    val multiValued: Boolean = false,
    /** Value list name, for ENUMERATION. */
    val enumeration: String? = null,
    /** For QUANTITY. */
    val dimension: String? = null,
    val required: Boolean = false,
    val immutable: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val minValue: BigDecimal? = null,
    val maxValue: BigDecimal? = null,
    val displayUnit: String? = null,
    val defaultValue: Any? = null,
    val sortOrder: Int = 0,
    val active: Boolean = true,
    val dependsOn: String? = null,
    val valueFilter: Map<String, List<String>>? = null,
    /** Calculated attribute: its CEL formula. */
    val formula: String? = null,
)

/** Null fields inherit from the parent type. */
data class TypeRulesConfig(
    /** Numbering scheme name. */
    val numberingScheme: String? = null,
    val revisionScheme: String? = null,
    val allowManualNumber: Boolean? = null,
)

data class LayoutConfig(
    val screen: String,
    val groups: List<LayoutGroupConfig>,
    val hidden: List<String> = emptyList(),
)

data class LayoutGroupConfig(
    val key: String,
    val labels: Map<String, String>,
    val columns: Int = 2,
    val attributes: List<String>,
)

enum class ChangeAction {
    CREATE,
    UPDATE,
    UNCHANGED,

    /** Can't be applied; the reason is in the details. Nothing is applied while there are conflicts. */
    CONFLICT,
}

/** One item of an import: e.g. kind `attribute`, key `Fastener.threadSize`. */
data class PlannedChange(
    val kind: String,
    val key: String,
    val action: ChangeAction,
    /** What changes (`required: false → true`), or why it conflicts. */
    val details: List<String> = emptyList(),
)

data class ImportPlan(
    /** True only when the import was applied (not a dry run, and without conflicts). */
    val applied: Boolean,
    val changes: List<PlannedChange>,
) {
    fun count(action: ChangeAction) = changes.count { it.action == action }
}

/**
 * Configuration as code (roadmap 1.10). Site administrators only (`ADMIN_REQUIRED`).
 *
 * Import adds and updates, it never deletes: things that exist here but not in the bundle are left
 * alone. Every import runs completely inside one transaction; a dry run (and any run with conflicts)
 * is rolled back, so the plan it returns is exactly what applying would do.
 */
interface ConfigurationService {
    fun export(actor: Actor): ConfigBundle

    /** @throws com.plm.platform.error.ValidationException `UNSUPPORTED_CONFIG_FORMAT` */
    fun import(
        actor: Actor,
        bundle: ConfigBundle,
        dryRun: Boolean,
    ): ImportPlan
}

data class ConfigurationImported(
    val created: Int,
    val updated: Int,
    /** `CREATE attribute Fastener.threadSize`, … */
    val changes: List<String>,
) : com.plm.platform.events.DomainEvent {
    override val eventType = "configuration.ConfigurationImported.v1"
    override val aggregateType = "configuration"
    override val aggregateId: java.util.UUID = java.util.UUID.nameUUIDFromBytes("configuration".toByteArray())
}
