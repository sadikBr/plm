package com.plm.extensions.domain

import com.plm.extensions.api.ScriptLimits
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import tools.jackson.databind.json.JsonMapper
import java.lang.management.ManagementFactory

/** Why a run was stopped: a limit, not the script's own error (scripts can't catch it). */
internal class ScriptLimitExceeded(
    message: String,
) : Error(message)

/** What a script may ask of the platform: reads as the triggering user, counted. */
internal interface ScriptApi {
    fun objectById(id: String): Any?

    fun objectByNumber(number: String): Any?
}

internal data class SandboxResult(
    val ok: Boolean,
    val result: Any?,
    val error: String?,
    val logs: List<String>,
    val durationMs: Int,
    val instructions: Long,
)

private class Budget(
    val deadlineNanos: Long,
    val startBytes: Long,
) {
    var instructions = 0L
    var apiCalls = 0
}

private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

private fun allocated(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())

/** Interpreted only (so every instruction is counted), no Java access, limits checked as it runs. */
private class SandboxContextFactory : ContextFactory() {
    override fun makeContext(): Context =
        super.makeContext().apply {
            optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 1_000
            setClassShutter { false }
            setWrapFactory(
                object : org.mozilla.javascript.WrapFactory() {
                    init {
                        isJavaPrimitiveWrap = false
                    }
                },
            )
        }

    override fun observeInstructionCount(
        cx: Context,
        instructionCount: Int,
    ) {
        val budget = cx.getThreadLocal("budget") as Budget
        budget.instructions += instructionCount
        if (budget.instructions > ScriptLimits.INSTRUCTIONS) {
            throw ScriptLimitExceeded("Stopped: more than ${ScriptLimits.INSTRUCTIONS} instructions")
        }
        if (System.nanoTime() > budget.deadlineNanos) throw ScriptLimitExceeded("Stopped: it ran out of time")
        if (allocated() - budget.startBytes > ScriptLimits.MEMORY_BYTES) {
            throw ScriptLimitExceeded("Stopped: it used more than ${ScriptLimits.MEMORY_BYTES / 1024 / 1024} MB")
        }
    }

    override fun hasFeature(
        cx: Context,
        featureIndex: Int,
    ): Boolean =
        when (featureIndex) {
            Context.FEATURE_STRICT_MODE, Context.FEATURE_ENHANCED_JAVA_ACCESS -> false
            else -> super.hasFeature(cx, featureIndex)
        }
}

/**
 * Runs server scripts (roadmap 5.14) in Rhino: a fresh scope per run with only the standard
 * objects (no `Packages`, `java`, files or network), `ctx` as plain JSON, and `plm.log`,
 * `plm.object`, `plm.find`. Time, instructions, allocated memory, API calls and output are limited.
 */
internal object ScriptSandbox {
    private val factory = SandboxContextFactory()
    private val json = JsonMapper.builder().build()

    /** Fails with a message when [source] doesn't compile or lacks [function]. */
    fun check(
        source: String,
        function: String,
    ): String? {
        val r = run(source, function, null, null, 1_000, probe = true)
        return r.error
    }

    fun run(
        source: String,
        function: String,
        input: Map<String, Any?>?,
        api: ScriptApi?,
        timeoutMs: Int,
        probe: Boolean = false,
    ): SandboxResult {
        val started = System.nanoTime()
        val logs = mutableListOf<String>()
        val budget = Budget(started + timeoutMs * 1_000_000L, allocated())
        val cx = factory.enterContext()
        cx.putThreadLocal("budget", budget)

        fun done(
            ok: Boolean,
            result: Any?,
            error: String?,
        ) = SandboxResult(
            ok,
            result,
            error,
            logs,
            ((System.nanoTime() - started) / 1_000_000).toInt(),
            budget.instructions,
        )
        try {
            if (source.toByteArray().size >
                ScriptLimits.SOURCE_BYTES
            ) {
                return done(false, null, "The script is too long")
            }
            val scope = cx.initSafeStandardObjects()
            ScriptableObject.deleteProperty(scope, "Packages")
            ScriptableObject.deleteProperty(scope, "java")
            ScriptableObject.deleteProperty(scope, "JavaImporter")
            val plm = cx.newObject(scope)

            fun fn(
                name: String,
                body: (Array<out Any?>) -> Any?,
            ) = ScriptableObject.putProperty(
                plm,
                name,
                object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable?,
                        args: Array<out Any?>,
                    ): Any? = body(args)
                },
            )
            fn("log") { args ->
                if (logs.size < ScriptLimits.LOG_LINES) {
                    logs +=
                        args
                            .joinToString(
                                " ",
                            ) { a -> if (a is Scriptable) stringify(cx, scope, a) ?: "" else Context.toString(a) }
                            .take(500)
                }
                Undefined.instance
            }

            fun call(
                args: Array<out Any?>,
                read: (String) -> Any?,
            ): Any? {
                if (++budget.apiCalls > ScriptLimits.API_CALLS) {
                    throw ScriptLimitExceeded("Stopped: more than ${ScriptLimits.API_CALLS} calls to plm")
                }
                val key = args.firstOrNull()?.let { Context.toString(it) } ?: return null
                val value = api?.let { read.invoke(key) } ?: return null
                return NativeJSON.parse(cx, scope, json.writeValueAsString(value)) { _, _, _, a -> a[1] }
            }
            fn("object") { args -> call(args) { api!!.objectById(it) } }
            fn("find") { args -> call(args) { api!!.objectByNumber(it) } }
            ScriptableObject.putProperty(scope, "plm", plm)

            cx.evaluateString(scope, source, "script", 1, null)
            val f =
                scope.get(function, scope) as? Function ?: return done(false, null, "Define a function $function(ctx)")
            if (probe) return done(true, null, null)
            val ctx =
                NativeJSON.parse(
                    cx,
                    scope,
                    json.writeValueAsString(input ?: emptyMap<String, Any?>()),
                ) { _, _, _, a -> a[1] }
            val out = f.call(cx, scope, scope, arrayOf(ctx))
            if (out == null || Undefined.isUndefined(out)) return done(true, null, null)
            val text = stringify(cx, scope, out) ?: return done(true, null, null)
            if (text.length > ScriptLimits.OUTPUT_BYTES) return done(false, null, "The result is too large")
            return done(true, json.readValue(text, Any::class.java), null)
        } catch (e: ScriptLimitExceeded) {
            return done(false, null, e.message)
        } catch (e: RhinoException) {
            return done(false, null, "${e.details()} (line ${e.lineNumber()})")
        } catch (e: RuntimeException) {
            return done(false, null, e.message ?: e.javaClass.simpleName)
        } finally {
            Context.exit()
        }
    }

    private fun stringify(
        cx: Context,
        scope: Scriptable,
        value: Any?,
    ): String? =
        NativeJSON.stringify(cx, scope, value, null, null).let {
            if (Undefined.isUndefined(it)) null else it.toString()
        }
}
