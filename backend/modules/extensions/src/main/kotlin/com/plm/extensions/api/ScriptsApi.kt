package com.plm.extensions.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** What a server script is for (roadmap 5.14), and the function it must define. */
enum class ScriptPurpose(
    val function: String,
) {
    /** `validate(ctx)`: a message (or a list) refuses the operation; nothing lets it through. */
    VALIDATION("validate"),

    /** `number(ctx)`: the new object's number, or nothing to leave it to the numbering scheme. */
    NUMBERING("number"),

    /** `run(ctx)`: a step of an automation (roadmap 5.16); returns anything JSON. */
    AUTOMATION("run"),
}

data class ServerScript(
    val id: UUID,
    val name: String,
    val purpose: ScriptPurpose,
    val description: String?,
    val source: String,
    /** Type names (subtypes included); empty: every type. */
    val types: List<String>,
    /** For validation: the operations it checks, e.g. `object.check-in`. */
    val operations: List<String>,
    val active: Boolean,
    /** Wall-clock limit of one run. */
    val timeoutMs: Int,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class ScriptSettings(
    val name: String,
    val purpose: ScriptPurpose,
    val source: String,
    val description: String? = null,
    val types: List<String> = emptyList(),
    val operations: List<String> = emptyList(),
    val active: Boolean = true,
    val timeoutMs: Int = 250,
)

/** One run of a script: what it returned or why it failed, and what it logged. */
data class ScriptRun(
    val id: UUID?,
    val scriptId: UUID?,
    val ranAt: Instant,
    val actor: String,
    /** `test`, an operation, `numbering` or an automation. */
    val trigger: String,
    val ok: Boolean,
    val durationMs: Int,
    val instructions: Long,
    val result: Any?,
    val error: String?,
    val logs: List<String>,
)

/** A test run: [source] (or the saved script's) with [input] as `ctx`. */
data class ScriptTest(
    val input: Map<String, Any?> = emptyMap(),
    val source: String? = null,
    val purpose: ScriptPurpose? = null,
)

/**
 * The limits every run has: [timeoutMs] of wall clock (per script, at most [MAX_TIMEOUT_MS]),
 * [INSTRUCTIONS] interpreted instructions, [MEMORY_BYTES] allocated, [API_CALLS] calls to `plm.*`,
 * [OUTPUT_BYTES] of result. Scripts see no Java classes, files or network.
 */
object ScriptLimits {
    const val MAX_TIMEOUT_MS = 2_000
    const val INSTRUCTIONS = 5_000_000L
    const val MEMORY_BYTES = 64L * 1024 * 1024
    const val API_CALLS = 25
    const val OUTPUT_BYTES = 64 * 1024
    const val SOURCE_BYTES = 64 * 1024
    const val LOG_LINES = 50
}

/**
 * Sandboxed server scripts (roadmap 5.14). Site administrators write them; they run as the user
 * whose action triggered them, and `plm.object(id)` / `plm.find(number)` read only what that user
 * may. Errors: `ADMIN_REQUIRED`, `SCRIPT_NOT_FOUND`, `INVALID_SCRIPT`, `DUPLICATE_SCRIPT_NAME`.
 */
interface ServerScriptService {
    fun list(actor: Actor): List<ServerScript>

    fun define(
        actor: Actor,
        settings: ScriptSettings,
    ): ServerScript

    fun update(
        actor: Actor,
        id: UUID,
        settings: ScriptSettings,
    ): ServerScript

    fun delete(
        actor: Actor,
        id: UUID,
    )

    /** The latest runs of a script, newest first. */
    fun runs(
        actor: Actor,
        id: UUID,
        limit: Int = 50,
    ): List<ScriptRun>

    /** Runs a script (saved or not) on sample input, recording nothing. */
    fun test(
        actor: Actor,
        id: UUID?,
        test: ScriptTest,
    ): ScriptRun

    /** Runs an automation script for [trigger] with [input]; the run is recorded. */
    fun runAutomation(
        actor: Actor,
        id: UUID,
        input: Map<String, Any?>,
        trigger: String,
    ): ScriptRun
}
