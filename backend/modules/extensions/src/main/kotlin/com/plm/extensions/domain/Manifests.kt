package com.plm.extensions.domain

import com.plm.access.api.Permission
import com.plm.extensions.api.EXTENSION_PERMISSIONS
import com.plm.extensions.api.ExtensionManifest
import com.plm.platform.error.ValidationException
import com.plm.platform.expression.ExpressionKind
import com.plm.platform.expression.Expressions
import com.plm.versioning.api.GuardedOperation
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule

private val ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*)+$")
private val ACTION_ID = Regex("^[a-z][a-z0-9-]{0,40}$")
private val CONFIG_KEY = Regex("^[a-zA-Z][a-zA-Z0-9]{0,40}$")
private val CONFIG_TYPES = setOf("string", "url", "secret", "number", "boolean", "choice")
private val PLACEMENTS = setOf("object.toolbar", "object.more")

/** `versioning.ObjectStateChanged.v1`, `versioning.*` or `*`. */
internal val EVENT_PATTERN = Regex("^(\\*|[a-z]+\\.(\\*|[A-Z][A-Za-z]+\\.v\\d+))$")
private val FORM_TYPES = setOf("string", "integer", "number", "boolean")
private val PLACEHOLDER = Regex("\\$\\{([a-zA-Z.]+)}")
private val URL_VARIABLES =
    setOf("object.number", "object.id", "object.name", "object.type", "object.state", "object.version", "user.id")
private const val MAX_SOURCE = 512 * 1024

/** Reads and checks manifests: everything wrong is reported at once, as `INVALID_MANIFEST` details. */
internal object Manifests {
    private val kotlin = KotlinModule.Builder().build()
    private val yaml =
        YAMLMapper
            .builder()
            .addModule(kotlin)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    /** For storing the parsed manifest. */
    val json: JsonMapper = JsonMapper.builder().addModule(kotlin).build()

    /** An installed manifest, as written (keeps the order of its settings); no checks. */
    fun read(source: String): ExtensionManifest? =
        runCatching { yaml.readValue(source, ExtensionManifest::class.java) }.getOrNull()

    /** YAML or JSON (JSON is YAML). */
    fun parse(source: String): ExtensionManifest {
        if (source.length > MAX_SOURCE) fail(listOf("The manifest is larger than ${MAX_SOURCE / 1024} KB"))
        val manifest =
            try {
                yaml.readValue(source, ExtensionManifest::class.java)
            } catch (e: JacksonException) {
                fail(listOf("Not a valid manifest: ${e.originalMessage}"))
            } ?: fail(listOf("The manifest is empty"))
        problems(manifest).takeIf { it.isNotEmpty() }?.let(::fail)
        return manifest
    }

    fun problems(m: ExtensionManifest): List<String> {
        val errors = mutableListOf<String>()
        if (!ID.matches(m.id)) errors += "id '${m.id}' must look like 'acme.erp-sync' (lower case, dot-separated)"
        if (m.name.isBlank() || m.name.length > 100) errors += "name must be 1-100 characters"
        if (Versions.parse(m.version) == null) errors += "version '${m.version}' must be a version like 1.2.0"
        if (runCatching { Versions.Range.parse(m.platform) }.isFailure) {
            errors += "platform '${m.platform}' must be a range like '>=1.0 <2.0'"
        }
        m.permissions.filter { it !in EXTENSION_PERMISSIONS }.forEach {
            errors += "Unknown permission '$it' (known: ${EXTENSION_PERMISSIONS.keys.joinToString()})"
        }
        if (m.actions.isNotEmpty() &&
            "ui:actions" !in m.permissions
        ) {
            errors += "Adding actions needs the 'ui:actions' permission"
        }
        if (m.seed != null && "config:seed" !in m.permissions) errors += "A seed needs the 'config:seed' permission"
        m.config.forEach { (key, f) ->
            if (!CONFIG_KEY.matches(key)) errors += "config key '$key' must be letters and digits"
            if (f.type !in CONFIG_TYPES) errors += "config '$key': type must be one of ${CONFIG_TYPES.joinToString()}"
            if (f.type == "choice" && f.options.isEmpty()) errors += "config '$key': a choice needs options"
        }
        m.actions.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
            errors +=
                "Action '$it' is declared twice"
        }
        for (a in m.actions) {
            val at = "action '${a.id}'"
            if (!ACTION_ID.matches(a.id)) errors += "$at: id must be lower case letters, digits and dashes"
            if (a.label.isBlank() || a.label.length > 60) errors += "$at: label must be 1-60 characters"
            if (a.placement !in PLACEMENTS) errors += "$at: placement must be ${PLACEMENTS.joinToString(" or ")}"
            if (a.requires != null && Permission.entries.none { it.name == a.requires }) {
                errors += "$at: requires must be one of ${Permission.entries.joinToString { it.name }}"
            }
            a.visibleWhen?.let { v ->
                Expressions.check(v, ExpressionKind.CONDITION).firstOrNull()?.let {
                    errors +=
                        "$at: visibleWhen: ${it.message}"
                }
            }
            val handlers = listOfNotNull(a.handler.url, a.handler.webhook)
            if (handlers.size != 1) errors += "$at: needs exactly one handler: url or webhook"
            a.handler.url?.let { errors += urlProblems(m, at, "url", it) }
            a.handler.webhook?.let { w ->
                if ("webhooks:call" !in m.permissions) errors += "$at: a webhook needs the 'webhooks:call' permission"
                errors += urlProblems(m, at, "webhook url", w.url)
                if (m.config[w.secret]?.type !=
                    "secret"
                ) {
                    errors += "$at: webhook secret must name a setting of type secret"
                }
                if (!w.background && w.timeoutSeconds !in 1..30) errors += "$at: timeoutSeconds must be 1 to 30"
                if (w.background &&
                    w.timeoutSeconds !in 1..3600
                ) {
                    errors +=
                        "$at: timeoutSeconds must be 1 to 3600 for a background action"
                }
            }
            a.form?.let { f ->
                f.properties.forEach { (name, field) ->
                    if (!CONFIG_KEY.matches(name)) errors += "$at: form field '$name' must be letters and digits"
                    if (field.type !in
                        FORM_TYPES
                    ) {
                        errors +=
                            "$at: form field '$name': type must be one of ${FORM_TYPES.joinToString()}"
                    }
                    if (field.enum.isNotEmpty() &&
                        field.type != "string"
                    ) {
                        errors += "$at: form field '$name': enum is for strings"
                    }
                }
                f.required.filter { it !in f.properties }.forEach { errors += "$at: form requires unknown field '$it'" }
                if (f.properties.isNotEmpty() &&
                    a.handler.webhook == null
                ) {
                    errors += "$at: a form needs a webhook handler to send its answers to"
                }
            }
        }
        if (m.rules.isNotEmpty() &&
            "rules:refuse" !in m.permissions
        ) {
            errors += "Rules need the 'rules:refuse' permission"
        }
        m.rules
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach { errors += "Rule '$it' is declared twice" }
        for (r in m.rules) {
            val at = "rule '${r.id}'"
            if (!ACTION_ID.matches(r.id)) errors += "$at: id must be lower case letters, digits and dashes"
            if (r.on.isEmpty()) errors += "$at: on: name the operations it applies to"
            r.on.filter { GuardedOperation.of(it) == null }.forEach {
                errors +=
                    "$at: unknown operation '$it' (known: ${GuardedOperation.entries.joinToString { o -> o.key }})"
            }
            Expressions.check(r.`when`, ExpressionKind.CONDITION).firstOrNull()?.let {
                errors +=
                    "$at: when: ${it.message}"
            }
            if (r.reject.isBlank() ||
                r.reject.length > 300
            ) {
                errors += "$at: reject must be a message of 1-300 characters"
            }
        }
        if (m.ui.isNotEmpty() &&
            "ui:panels" !in m.permissions
        ) {
            errors += "Panels and tabs need the 'ui:panels' permission"
        }
        m.ui
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach { errors += "UI '$it' is declared twice" }
        for (u in m.ui) {
            val at = "ui '${u.id}'"
            if (!ACTION_ID.matches(u.id)) errors += "$at: id must be lower case letters, digits and dashes"
            if (u.slot !in UI_SLOTS) errors += "$at: slot must be one of ${UI_SLOTS.joinToString()}"
            if (u.title.isBlank() || u.title.length > 40) errors += "$at: title must be 1-40 characters"
            if (u.height !in 60..2000) errors += "$at: height must be 60 to 2000"
            errors += urlProblems(m, at, "url", u.url)
        }
        if (m.endpoints.isNotEmpty() && "endpoints:serve" !in m.permissions) {
            errors += "Endpoints need the 'endpoints:serve' permission"
        }
        m.endpoints.groupBy { it.path }.filterValues { it.size > 1 }.keys.forEach {
            errors +=
                "Endpoint path '$it' is declared twice"
        }
        for (e in m.endpoints) {
            val at = "endpoint '${e.id}'"
            if (!ACTION_ID.matches(e.id)) errors += "$at: id must be lower case letters, digits and dashes"
            if (!ENDPOINT_PATH.matches(e.path)) {
                errors +=
                    "$at: path must be lower case letters, digits, dashes and slashes"
            }
            e.methods.filter { it !in ENDPOINT_METHODS }.forEach {
                errors +=
                    "$at: method $it isn't one of ${ENDPOINT_METHODS.joinToString()}"
            }
            if (e.methods.isEmpty()) errors += "$at: methods: name at least one"
            if (e.timeoutSeconds !in 1..30) errors += "$at: timeoutSeconds must be 1 to 30"
            if (m.config[e.secret]?.type != "secret") errors += "$at: secret must name a setting of type secret"
            errors += urlProblems(m, at, "url", e.url)
            if (PLACEHOLDER.findAll(e.url).any { !it.groupValues[1].startsWith("config.") }) {
                errors += "$at: url can only use \${config.<key>}"
            }
        }
        if (m.subscriptions.isNotEmpty() &&
            "events:subscribe" !in m.permissions
        ) {
            errors += "Subscriptions need the 'events:subscribe' permission"
        }
        m.subscriptions.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
            errors +=
                "Subscription '$it' is declared twice"
        }
        for (sub in m.subscriptions) {
            val at = "subscription '${sub.id}'"
            if (!ACTION_ID.matches(sub.id)) errors += "$at: id must be lower case letters, digits and dashes"
            if (sub.events.isEmpty()) errors += "$at: events: name the events it wants"
            sub.events.filterNot { EVENT_PATTERN.matches(it) }.forEach {
                errors += "$at: '$it' isn't an event type (e.g. versioning.ObjectStateChanged.v1, versioning.* or *)"
            }
            sub.filter?.let { f ->
                Expressions.check(f, ExpressionKind.CONDITION).firstOrNull()?.let {
                    errors +=
                        "$at: filter: ${it.message}"
                }
            }
            val w = sub.deliver.webhook
            errors += urlProblems(m, at, "webhook url", w.url)
            if (m.config[w.secret]?.type != "secret") errors += "$at: webhook secret must name a setting of type secret"
            if (w.retries !in 1..20) errors += "$at: retries must be 1 to 20"
        }
        return errors
    }

    private fun urlProblems(
        m: ExtensionManifest,
        at: String,
        what: String,
        url: String,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (!url.startsWith("https://") && !url.startsWith("http://") && !url.startsWith("\${config.")) {
            errors += "$at: the $what must start with https:// (or a config setting)"
        }
        PLACEHOLDER.findAll(url).map { it.groupValues[1] }.forEach { name ->
            val key = name.removePrefix("config.")
            when {
                name.startsWith("config.") && m.config[key] == null ->
                    errors +=
                        "$at: $what uses unknown setting '$key'"
                name.startsWith("config.") && m.config[key]?.type == "secret" ->
                    errors +=
                        "$at: $what can't contain the secret '$key'"
                !name.startsWith("config.") && name !in URL_VARIABLES ->
                    errors += "$at: $what can use ${URL_VARIABLES.joinToString { "\${$it}" }} and \${config.<key>}"
            }
        }
        return errors
    }

    private fun fail(errors: List<String>): Nothing =
        throw ValidationException(
            "INVALID_MANIFEST",
            "The manifest isn't valid: ${errors.first()}",
            mapOf(
                "errors" to errors,
            ),
        )
}

/** Dotted numeric versions (`1.3.0`; a `-beta` suffix is ignored for ordering) and ranges of them. */
internal object Versions {
    fun parse(v: String): List<Int>? {
        val core = v.trim().substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }

    fun compare(
        a: String,
        b: String,
    ): Int {
        val x = parse(a) ?: emptyList()
        val y = parse(b) ?: emptyList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Space-separated comparisons that must all hold: `>=1.0 <2.0`, `^1.2` (same major), `1.x`. */
    class Range private constructor(
        private val tests: List<(String) -> Boolean>,
    ) {
        fun includes(version: String) = tests.all { it(version) }

        companion object {
            fun parse(text: String): Range {
                val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(parts.isNotEmpty())
                return Range(parts.map(::test))
            }

            private fun test(p: String): (String) -> Boolean {
                fun v(s: String) = s.also { require(Versions.parse(it) != null) }
                return when {
                    p.startsWith(">=") -> v(p.drop(2)).let { b -> { compare(it, b) >= 0 } }
                    p.startsWith("<=") -> v(p.drop(2)).let { b -> { compare(it, b) <= 0 } }
                    p.startsWith(">") -> v(p.drop(1)).let { b -> { compare(it, b) > 0 } }
                    p.startsWith("<") -> v(p.drop(1)).let { b -> { compare(it, b) < 0 } }
                    p.startsWith("=") -> v(p.drop(1)).let { b -> { compare(it, b) == 0 } }
                    p.startsWith("^") ->
                        v(p.drop(1)).let { b ->
                            {
                                compare(it, b) >= 0 &&
                                    Versions.parse(it)!![0] == Versions.parse(b)!![0]
                            }
                        }
                    p.endsWith(".x") ->
                        v(p.dropLast(2)).let { b ->
                            { Versions.parse(it)!![0] == Versions.parse(b)!![0] }
                        }
                    else -> v(p).let { b -> { compare(it, b) == 0 } }
                }
            }
        }
    }
}

internal val UI_SLOTS = listOf("object.panel", "object.tab")
internal val ENDPOINT_METHODS = listOf("GET", "POST", "PUT", "DELETE")
private val ENDPOINT_PATH = Regex("^[a-z0-9][a-z0-9/_-]{0,79}$")
