package com.plm.configuration.domain

import com.plm.access.api.ContextKind
import com.plm.access.api.ContextTemplateService
import com.plm.access.api.SaveContextTemplate
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamRoleService
import com.plm.access.api.TeamRoleSettings
import com.plm.configuration.api.AttributeConfig
import com.plm.configuration.api.ChangeAction
import com.plm.configuration.api.ConfigBundle
import com.plm.configuration.api.ConfigurationImported
import com.plm.configuration.api.ConfigurationService
import com.plm.configuration.api.ContextTemplateConfig
import com.plm.configuration.api.EnumerationConfig
import com.plm.configuration.api.EnumerationValueConfig
import com.plm.configuration.api.ImportPlan
import com.plm.configuration.api.LayoutConfig
import com.plm.configuration.api.LayoutGroupConfig
import com.plm.configuration.api.LifecycleConfig
import com.plm.configuration.api.LifecycleStateConfig
import com.plm.configuration.api.LifecycleTransitionConfig
import com.plm.configuration.api.NumberingSchemeConfig
import com.plm.configuration.api.PlannedChange
import com.plm.configuration.api.TeamRoleConfig
import com.plm.configuration.api.TypeConfig
import com.plm.configuration.api.TypeRulesConfig
import com.plm.lifecycle.api.CreateLifecycleTemplate
import com.plm.lifecycle.api.LifecycleService
import com.plm.lifecycle.api.LifecycleState
import com.plm.lifecycle.api.LifecycleTemplate
import com.plm.lifecycle.api.LifecycleTransition
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.DomainEvents
import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeSettings
import com.plm.types.api.CreateNumberingScheme
import com.plm.types.api.CreateSubtype
import com.plm.types.api.DataType
import com.plm.types.api.DefineAttribute
import com.plm.types.api.EnumerationService
import com.plm.types.api.EnumerationValue
import com.plm.types.api.LayoutGroup
import com.plm.types.api.LayoutScreen
import com.plm.types.api.LayoutService
import com.plm.types.api.NumberingSchemeId
import com.plm.types.api.RevisionScheme
import com.plm.types.api.SaveLayout
import com.plm.types.api.TypeDefinition
import com.plm.types.api.TypeId
import com.plm.types.api.TypeRuleService
import com.plm.types.api.TypeRuleSettings
import com.plm.types.api.TypeService
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal

@Service
internal class DefaultConfigurationService(
    private val types: TypeService,
    private val attributes: AttributeService,
    private val enumerations: EnumerationService,
    private val rules: TypeRuleService,
    private val layouts: LayoutService,
    private val lifecycles: LifecycleService,
    private val roles: TeamRoleService,
    private val contextTemplates: ContextTemplateService,
    private val events: DomainEvents,
    transactions: PlatformTransactionManager,
) : ConfigurationService {
    private val outer = TransactionTemplate(transactions)

    /** Each change runs under a savepoint, so one failure is reported without spoiling the rest. */
    private val step =
        TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }

    // ---------- Export ----------

    override fun export(actor: Actor): ConfigBundle {
        requireAdmin(actor)
        return outer.execute {
            it.setRollbackOnly() // read only
            val allTypes = types.list(actor)
            val typeNames = allTypes.associate { t -> t.id to t.name }
            val enumNames = enumerations.list(actor).associate { e -> e.id to e.name }
            val schemeNames = rules.numberingSchemes(actor).associate { s -> s.id to s.name }
            val lifecycleList = lifecycles.list(actor)
            val lifecycleNames = lifecycleList.associate { l -> l.id to l.name }
            val ownLifecycles = lifecycles.assignments(actor)

            ConfigBundle(
                teamRoles =
                    roles.list(actor).sortedBy { r -> r.role.key }.map { r ->
                        TeamRoleConfig(r.role.key, r.labels.sorted(), r.description, r.active, r.sortOrder)
                    },
                enumerations =
                    enumerations.list(actor).sortedBy { e -> e.name }.map { e ->
                        EnumerationConfig(
                            e.name,
                            e.labels.sorted(),
                            e.values.map { v ->
                                EnumerationValueConfig(v.key, v.labels.sorted(), v.active)
                            },
                        )
                    },
                numberingSchemes =
                    rules.numberingSchemes(actor).sortedBy { s -> s.name }.map { s ->
                        NumberingSchemeConfig(s.name, s.prefix, s.padding, s.suffix)
                    },
                lifecycles = lifecycleList.sortedBy { l -> l.name }.map { l -> l.toConfig() },
                contextTemplates =
                    contextTemplates
                        .list(actor)
                        .filter { t ->
                            t.organizationId == null
                        }.sortedWith(compareBy({ t -> t.kind }, { t -> t.name }))
                        .map { t ->
                            ContextTemplateConfig(t.name, t.kind.name, t.description, t.content)
                        },
                types =
                    parentsFirst(allTypes).map { t ->
                        val own =
                            rules.rules(actor, t.id).let { r ->
                                r to
                                    r.inheritedFrom.filterValues { from -> from == t.id }.keys
                            }
                        val (effective, ownKeys) = own
                        TypeConfig(
                            name = t.name,
                            displayName = t.displayName,
                            parent = t.parentId?.let(typeNames::getValue),
                            baseClass = t.baseClass.name,
                            attributes =
                                attributes
                                    .effective(actor, t.id, includeInactive = true)
                                    .filter { a -> a.typeId == t.id }
                                    .map { a -> a.toConfig(enumNames) },
                            rules =
                                if (ownKeys.isEmpty()) {
                                    null
                                } else {
                                    TypeRulesConfig(
                                        numberingScheme =
                                            effective.numberingSchemeId
                                                ?.takeIf {
                                                    "numberingScheme" in
                                                        ownKeys
                                                }?.let(schemeNames::getValue),
                                        revisionScheme =
                                            effective.revisionScheme.name.takeIf {
                                                "revisionScheme" in
                                                    ownKeys
                                            },
                                        allowManualNumber =
                                            effective.allowManualNumber.takeIf {
                                                "allowManualNumber" in
                                                    ownKeys
                                            },
                                    )
                                },
                            lifecycle = ownLifecycles[t.id]?.let(lifecycleNames::getValue),
                            layouts =
                                layouts.own(actor, t.id).sortedBy { l -> l.screen }.map { l ->
                                    LayoutConfig(
                                        l.screen.name,
                                        l.groups.map { g ->
                                            LayoutGroupConfig(g.key, g.labels.sorted(), g.columns, g.attributes)
                                        },
                                        l.hidden,
                                    )
                                },
                        )
                    },
            )
        }
    }

    // ---------- Import ----------

    override fun import(
        actor: Actor,
        bundle: ConfigBundle,
        dryRun: Boolean,
    ): ImportPlan {
        requireAdmin(actor)
        if (bundle.format != ConfigBundle.FORMAT) {
            throw ValidationException(
                "UNSUPPORTED_CONFIG_FORMAT",
                "Expected a '${ConfigBundle.FORMAT}' bundle, got '${bundle.format}'",
                mapOf("format" to bundle.format),
            )
        }
        return outer.execute { tx ->
            val changes = Importer(actor, bundle).run()
            val conflicts = changes.any { it.action == ChangeAction.CONFLICT }
            val applied = !dryRun && !conflicts
            if (applied) {
                events.publish(
                    actor,
                    ConfigurationImported(
                        created = changes.count { it.action == ChangeAction.CREATE },
                        updated = changes.count { it.action == ChangeAction.UPDATE },
                        changes =
                            changes
                                .filter {
                                    it.action != ChangeAction.UNCHANGED
                                }.map { "${it.action} ${it.kind} ${it.key}" },
                    ),
                )
            } else {
                tx.setRollbackOnly()
            }
            ImportPlan(applied, changes)
        }
    }

    /** One import run: walks the bundle in dependency order, comparing and (inside the transaction) applying. */
    private inner class Importer(
        val actor: Actor,
        val bundle: ConfigBundle,
    ) {
        val changes = mutableListOf<PlannedChange>()

        fun run(): List<PlannedChange> {
            importRoles()
            importEnumerations()
            importSchemes()
            importLifecycles()
            val typeIds = importTypes()
            for (t in bundle.types) typeIds[t.name]?.let { importAttributes(t, it) }
            for (t in bundle.types) typeIds[t.name]?.let { importRules(t, it) }
            for (t in bundle.types) typeIds[t.name]?.let { importTypeLifecycle(t, it) }
            for (t in bundle.types) typeIds[t.name]?.let { importLayouts(t, it) }
            importContextTemplates()
            return changes
        }

        /** Records the change and, unless it's UNCHANGED, applies it under a savepoint. */
        fun change(
            kind: String,
            key: String,
            action: ChangeAction,
            details: List<String> = emptyList(),
            apply: () -> Unit = {},
        ): Boolean {
            if (action == ChangeAction.UNCHANGED || action == ChangeAction.CONFLICT) {
                changes += PlannedChange(kind, key, action, details)
                return action == ChangeAction.UNCHANGED
            }
            return try {
                step.executeWithoutResult { apply() }
                changes += PlannedChange(kind, key, action, details)
                true
            } catch (e: PlmException) {
                changes += PlannedChange(kind, key, ChangeAction.CONFLICT, problemDetails(e))
                false
            }
        }

        fun importContextTemplates() {
            val existing =
                contextTemplates.list(actor).filter { it.organizationId == null }.associateBy {
                    it.kind.name to
                        it.name
                }
            for (t in bundle.contextTemplates) {
                val key = "${t.kind}/${t.name}"
                val kind = runCatching { ContextKind.valueOf(t.kind) }.getOrNull()
                if (kind == null) {
                    change("contextTemplate", key, ChangeAction.CONFLICT, listOf("unknown kind '${t.kind}'"))
                    continue
                }
                val command = SaveContextTemplate(t.name, kind, t.description, t.content)
                val current = existing[t.kind to t.name]
                if (current == null) {
                    change("contextTemplate", key, ChangeAction.CREATE) { contextTemplates.create(actor, command) }
                } else {
                    val diff =
                        diff(
                            "description" to (current.description to t.description),
                            "rules" to (current.content.policies.toSet() to t.content.policies.toSet()),
                            "members" to (current.content.members.toSet() to t.content.members.toSet()),
                            "sharedTeams" to (current.content.sharedTeams to t.content.sharedTeams),
                        )
                    change(
                        "contextTemplate",
                        key,
                        updateOrNot(diff),
                        diff,
                    ) { contextTemplates.update(actor, current.id, command) }
                }
            }
        }

        fun importRoles() {
            val existing = roles.list(actor).associateBy { it.role.key }
            for (r in bundle.teamRoles) {
                val settings = TeamRoleSettings(r.labels, r.description, r.active, r.sortOrder)
                val current = existing[r.key]
                if (current == null) {
                    change("teamRole", r.key, ChangeAction.CREATE) { roles.create(actor, TeamRole(r.key), settings) }
                } else {
                    val diff =
                        diff(
                            "labels" to (current.labels to r.labels),
                            "description" to (current.description to r.description),
                            "active" to (current.active to r.active),
                            "sortOrder" to (current.sortOrder to r.sortOrder),
                        )
                    change(
                        "teamRole",
                        r.key,
                        updateOrNot(diff),
                        diff,
                    ) { roles.update(actor, TeamRole(r.key), settings) }
                }
            }
        }

        fun importEnumerations() {
            val existing = enumerations.list(actor).associateBy { it.name }
            for (e in bundle.enumerations) {
                val values = e.values.map { EnumerationValue(it.key, it.labels, it.active) }
                val current = existing[e.name]
                if (current == null) {
                    change(
                        "enumeration",
                        e.name,
                        ChangeAction.CREATE,
                    ) { enumerations.create(actor, e.name, e.labels, values) }
                    continue
                }
                // Values only here are kept (values are never removed), after the bundle's.
                val bundleKeys = values.map { it.key }.toSet()
                val keptHere = current.values.filter { it.key !in bundleKeys }
                val merged = values + keptHere
                val before = current.values.associateBy { it.key }
                val diff =
                    diff("labels" to (current.labels to e.labels)) +
                        values.mapNotNull { v ->
                            val b = before[v.key]
                            when {
                                b == null -> "value ${v.key}: added"
                                b.active != v.active ->
                                    "value ${v.key}: ${if (v.active) "reactivated" else "deactivated"}"
                                b.labels != v.labels -> "value ${v.key}: labels ${show(b.labels)} → ${show(v.labels)}"
                                else -> null
                            }
                        } +
                        listOfNotNull(
                            "values reordered".takeIf {
                                current.values.map { it.key } != merged.map { it.key } &&
                                    before.keys.containsAll(values.map { it.key })
                            },
                            keptHere.takeIf { it.isNotEmpty() }?.let { k ->
                                "keeps ${k.size} value(s) not in the bundle: ${k.joinToString { it.key }}"
                            },
                        )
                val changed = current.labels != e.labels || current.values != merged
                change(
                    "enumeration",
                    e.name,
                    if (changed) ChangeAction.UPDATE else ChangeAction.UNCHANGED,
                    if (changed) diff else emptyList(),
                ) {
                    enumerations.update(actor, current.id, e.labels, merged)
                }
            }
        }

        fun importSchemes() {
            val existing = rules.numberingSchemes(actor).associateBy { it.name }
            for (s in bundle.numberingSchemes) {
                val current = existing[s.name]
                if (current == null) {
                    change("numberingScheme", s.name, ChangeAction.CREATE) {
                        rules.createNumberingScheme(
                            actor,
                            CreateNumberingScheme(s.name, s.prefix, s.padding, s.suffix, 1),
                        )
                    }
                } else if (current.prefix != s.prefix || current.padding != s.padding || current.suffix != s.suffix) {
                    change(
                        "numberingScheme",
                        s.name,
                        ChangeAction.CONFLICT,
                        listOf(
                            "exists with format ${current.prefix}${"#".repeat(
                                current.padding,
                            )}${current.suffix}; numbering schemes can't be changed once created",
                        ),
                    )
                } else {
                    change("numberingScheme", s.name, ChangeAction.UNCHANGED)
                }
            }
        }

        fun importLifecycles() {
            val existing = lifecycles.list(actor).associateBy { it.name }
            for (l in bundle.lifecycles) {
                val current = existing[l.name]
                if (current == null) {
                    change("lifecycle", l.name, ChangeAction.CREATE) {
                        lifecycles.create(actor, l.toCommand())
                    }
                } else if (current.toConfig().normalized() != l.normalized()) {
                    // A changed lifecycle becomes a new version; objects keep the version they have.
                    change(
                        "lifecycle",
                        l.name,
                        ChangeAction.UPDATE,
                        listOf("new version ${current.version + 1} (objects keep version ${current.version})"),
                    ) {
                        lifecycles.update(actor, current.id, l.toCommand())
                    }
                } else {
                    change("lifecycle", l.name, ChangeAction.UNCHANGED)
                }
            }
        }

        /** @return type ids by name, for the bundle's types that exist (or were created). */
        fun importTypes(): MutableMap<String, TypeId> {
            val existing = types.list(actor).associateBy { it.name }
            val ids = existing.mapValues { it.value.id }.toMutableMap()
            val byName = existing.toMutableMap()
            for (t in bundle.types) {
                val current = byName[t.name]
                if (current != null) {
                    val parentName = current.parentId?.let { p -> byName.values.find { it.id == p }?.name }
                    when {
                        parentName != t.parent || current.baseClass.name != t.baseClass ->
                            change(
                                "type",
                                t.name,
                                ChangeAction.CONFLICT,
                                listOf(
                                    "exists as a ${current.baseClass} type under ${parentName ?: "no parent"}; a type's place in the hierarchy can't change",
                                ),
                            )
                        current.displayName != t.displayName ->
                            change(
                                "type",
                                t.name,
                                ChangeAction.CONFLICT,
                                listOf(
                                    "display name differs (${current.displayName} → ${t.displayName}); renaming types isn't supported yet",
                                ),
                            )
                        else -> change("type", t.name, ChangeAction.UNCHANGED)
                    }
                    continue
                }
                val parentId = t.parent?.let { ids[it] }
                if (parentId == null) {
                    change(
                        "type",
                        t.name,
                        ChangeAction.CONFLICT,
                        listOf(
                            "its parent type '${t.parent}' doesn't exist here and isn't created by the bundle (new base types can't be created)",
                        ),
                    )
                    continue
                }
                var created: TypeDefinition? = null
                change("type", t.name, ChangeAction.CREATE, listOf("subtype of ${t.parent}")) {
                    created = types.createSubtype(actor, CreateSubtype(parentId, t.name, t.displayName))
                }
                created?.let {
                    ids[t.name] = it.id
                    byName[t.name] = it
                }
            }
            return ids
        }

        fun importAttributes(
            t: TypeConfig,
            typeId: TypeId,
        ) {
            val enumIds = enumerations.list(actor).associate { it.name to it.id }
            val current = attributes.effective(actor, typeId, includeInactive = true).associateBy { it.name }
            for (a in t.attributes) {
                val key = "${t.name}.${a.name}"
                val dataType = runCatching { DataType.valueOf(a.dataType) }.getOrNull()
                if (dataType == null) {
                    change("attribute", key, ChangeAction.CONFLICT, listOf("unknown data type '${a.dataType}'"))
                    continue
                }
                val enumerationId = a.enumeration?.let { enumIds[it] }
                if (a.enumeration != null && enumerationId == null) {
                    change(
                        "attribute",
                        key,
                        ChangeAction.CONFLICT,
                        listOf("value list '${a.enumeration}' doesn't exist"),
                    )
                    continue
                }
                val settings = a.settings()
                val existing = current[a.name]
                if (existing == null) {
                    change("attribute", key, ChangeAction.CREATE) {
                        attributes.define(
                            actor,
                            DefineAttribute(
                                typeId,
                                a.name,
                                dataType,
                                a.multiValued,
                                enumerationId,
                                a.dimension,
                                settings,
                            ),
                        )
                    }
                    continue
                }
                if (existing.typeId != typeId) {
                    change(
                        "attribute",
                        key,
                        ChangeAction.CONFLICT,
                        listOf("an attribute with this name is already defined on another type of the hierarchy"),
                    )
                    continue
                }
                val fixed =
                    diff(
                        "dataType" to (existing.dataType.name to a.dataType),
                        "multiValued" to (existing.multiValued to a.multiValued),
                        "enumeration" to (existing.enumerationId to enumerationId),
                        "dimension" to (existing.dimension to a.dimension?.uppercase()),
                    )
                if (fixed.isNotEmpty()) {
                    change("attribute", key, ChangeAction.CONFLICT, fixed.map { "$it (can't change after definition)" })
                    continue
                }
                val diff = settingsDiff(existing, a)
                change("attribute", key, updateOrNot(diff), diff) { attributes.update(actor, existing.id, settings) }
            }
        }

        fun importRules(
            t: TypeConfig,
            typeId: TypeId,
        ) {
            val wanted = t.rules ?: return
            val schemes = rules.numberingSchemes(actor)
            val schemeIds = schemes.associate { it.name to it.id }
            val schemeNames: Map<NumberingSchemeId?, String> = schemes.associate { it.id to it.name }
            val schemeId = wanted.numberingScheme?.let { schemeIds[it] }
            if (wanted.numberingScheme != null && schemeId == null) {
                change(
                    "typeRules",
                    t.name,
                    ChangeAction.CONFLICT,
                    listOf("numbering scheme '${wanted.numberingScheme}' doesn't exist"),
                )
                return
            }
            val revision = wanted.revisionScheme?.let { runCatching { RevisionScheme.valueOf(it) }.getOrNull() }
            if (wanted.revisionScheme != null && revision == null) {
                change(
                    "typeRules",
                    t.name,
                    ChangeAction.CONFLICT,
                    listOf("unknown revision scheme '${wanted.revisionScheme}'"),
                )
                return
            }
            val effective = rules.rules(actor, typeId)
            val own = effective.inheritedFrom.filterValues { it == typeId }.keys
            val diff =
                diff(
                    "numberingScheme" to
                        (
                            schemeNames[effective.numberingSchemeId?.takeIf { "numberingScheme" in own }] to
                                wanted.numberingScheme
                        ),
                    "revisionScheme" to (effective.revisionScheme.takeIf { "revisionScheme" in own } to revision),
                    "allowManualNumber" to
                        (effective.allowManualNumber.takeIf { "allowManualNumber" in own } to wanted.allowManualNumber),
                )
            change("typeRules", t.name, updateOrNot(diff), diff) {
                rules.updateRules(actor, typeId, TypeRuleSettings(schemeId, revision, wanted.allowManualNumber))
            }
        }

        fun importTypeLifecycle(
            t: TypeConfig,
            typeId: TypeId,
        ) {
            val wanted = t.lifecycle ?: return
            val template = lifecycles.list(actor).find { it.name == wanted }
            if (template == null) {
                change("typeLifecycle", t.name, ChangeAction.CONFLICT, listOf("lifecycle '$wanted' doesn't exist"))
                return
            }
            val current = lifecycles.assignments(actor)[typeId]
            change(
                "typeLifecycle",
                t.name,
                if (current ==
                    template.id
                ) {
                    ChangeAction.UNCHANGED
                } else {
                    ChangeAction.UPDATE
                },
                listOf("lifecycle → $wanted"),
            ) {
                lifecycles.assign(actor, typeId, template.id)
            }
        }

        fun importLayouts(
            t: TypeConfig,
            typeId: TypeId,
        ) {
            val current = layouts.own(actor, typeId).associateBy { it.screen.name }
            for (l in t.layouts) {
                val key = "${t.name}/${l.screen}"
                val screen = runCatching { LayoutScreen.valueOf(l.screen) }.getOrNull()
                if (screen == null) {
                    change("layout", key, ChangeAction.CONFLICT, listOf("unknown screen '${l.screen}'"))
                    continue
                }
                val groups = l.groups.map { LayoutGroup(it.key, it.labels, it.columns, it.attributes) }
                val existing = current[l.screen]
                val action =
                    when {
                        existing == null -> ChangeAction.CREATE
                        existing.groups == groups && existing.hidden == l.hidden -> ChangeAction.UNCHANGED
                        else -> ChangeAction.UPDATE
                    }
                change(
                    "layout",
                    key,
                    action,
                    if (action ==
                        ChangeAction.UPDATE
                    ) {
                        listOf("groups: ${l.groups.joinToString { it.key }}")
                    } else {
                        emptyList()
                    },
                ) {
                    layouts.save(actor, typeId, screen, SaveLayout(groups, l.hidden))
                }
            }
        }
    }

    // ---------- Helpers ----------

    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators can export or import configuration",
            )
        }
    }

    private fun problemDetails(e: PlmException): List<String> {
        @Suppress("UNCHECKED_CAST")
        val errors = (e as? ValidationException)?.details?.get("errors") as? List<Any?>
        return errors?.map { if (it is Map<*, *>) "${it["attribute"] ?: ""}: ${it["message"]}" else it.toString() }
            ?: listOf("${e.code}: ${e.message}")
    }

    private fun updateOrNot(diff: List<String>) = if (diff.isEmpty()) ChangeAction.UNCHANGED else ChangeAction.UPDATE

    /** `name: before → after` for every pair that differs (numbers compared by value). */
    private fun diff(vararg fields: Pair<String, Pair<Any?, Any?>>): List<String> =
        fields.mapNotNull { (name, values) ->
            val (before, after) = values
            val same =
                if (before is BigDecimal && after is BigDecimal) before.compareTo(after) == 0 else before == after
            if (same) null else "$name: ${show(before)} → ${show(after)}"
        }

    private fun show(v: Any?): String =
        when (v) {
            null -> "—"
            is BigDecimal -> v.stripTrailingZeros().toPlainString()
            is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }
            is Collection<*> -> "${v.size} item(s)"
            else -> v.toString()
        }

    private fun settingsDiff(
        d: AttributeDefinition,
        a: AttributeConfig,
    ) = diff(
        "labels" to (d.labels to a.labels),
        "description" to (d.description to a.description),
        "required" to (d.required to a.required),
        "immutable" to (d.immutable to a.immutable),
        "minLength" to (d.minLength to a.minLength),
        "maxLength" to (d.maxLength to a.maxLength),
        "pattern" to (d.pattern to a.pattern),
        "minValue" to (d.minValue to a.minValue),
        "maxValue" to (d.maxValue to a.maxValue),
        "displayUnit" to (d.displayUnit to a.displayUnit),
        "defaultValue" to (normalizeJson(d.defaultValue) to normalizeJson(a.defaultValue)),
        "sortOrder" to (d.sortOrder to a.sortOrder),
        "active" to (d.active to a.active),
    )
}

/** JSON numbers may arrive as Int, Long, Double or BigDecimal: compare them by value. */
private fun normalizeJson(v: Any?): Any? =
    when (v) {
        is Number -> BigDecimal(v.toString()).stripTrailingZeros()
        is Map<*, *> -> v.mapValues { normalizeJson(it.value) }
        is List<*> -> v.map(::normalizeJson)
        else -> v
    }

private fun Map<String, String>.sorted(): Map<String, String> = toSortedMap()

private fun AttributeConfig.settings() =
    AttributeSettings(
        labels = labels,
        description = description,
        required = required,
        immutable = immutable,
        minLength = minLength,
        maxLength = maxLength,
        pattern = pattern,
        minValue = minValue,
        maxValue = maxValue,
        displayUnit = displayUnit,
        defaultValue = defaultValue,
        sortOrder = sortOrder,
        active = active,
    )

private fun AttributeDefinition.toConfig(enumNames: Map<com.plm.types.api.EnumerationId, String>) =
    AttributeConfig(
        name = name,
        labels = labels.toSortedMap(),
        description = description,
        dataType = dataType.name,
        multiValued = multiValued,
        enumeration = enumerationId?.let(enumNames::getValue),
        dimension = dimension,
        required = required,
        immutable = immutable,
        minLength = minLength,
        maxLength = maxLength,
        pattern = pattern,
        minValue = minValue,
        maxValue = maxValue,
        displayUnit = displayUnit,
        defaultValue = defaultValue,
        sortOrder = sortOrder,
        active = active,
    )

private fun LifecycleConfig.toCommand() =
    CreateLifecycleTemplate(
        name,
        labels,
        initialState,
        states.map {
            LifecycleState(
                it.key,
                it.labels,
                it.editable,
                it.revisable,
                it.requiredAttributes,
                it.revisionScheme?.let(RevisionScheme::valueOf),
            )
        },
        transitions.map { LifecycleTransition(it.from, it.to) },
    )

private fun LifecycleTemplate.toConfig() =
    LifecycleConfig(
        name = name,
        labels = labels.toSortedMap(),
        initialState = initialState,
        states =
            states.map {
                LifecycleStateConfig(
                    it.key,
                    it.labels.toSortedMap(),
                    it.editable,
                    it.revisable,
                    it.requiredAttributes,
                    it.revisionScheme?.name,
                )
            },
        transitions = transitions.map { LifecycleTransitionConfig(it.from, it.to) },
    )

/** Transition order doesn't matter when comparing lifecycles. */
private fun LifecycleConfig.normalized() =
    copy(
        labels = labels.toSortedMap(),
        states = states.map { it.copy(labels = it.labels.toSortedMap()) },
        transitions = transitions.sortedWith(compareBy({ it.from }, { it.to })),
    )

/** Parents before children, siblings by name. */
private fun parentsFirst(all: List<TypeDefinition>): List<TypeDefinition> {
    val children = all.groupBy { it.parentId }
    val out = mutableListOf<TypeDefinition>()

    fun walk(parent: TypeId?) {
        for (t in children[parent].orEmpty().sortedBy { it.name }) {
            out += t
            walk(t.id)
        }
    }
    walk(null)
    return out
}
