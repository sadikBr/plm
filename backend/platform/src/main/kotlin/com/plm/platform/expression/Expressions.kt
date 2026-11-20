package com.plm.platform.expression

import dev.cel.common.CelOptions
import dev.cel.common.CelValidationException
import dev.cel.common.types.SimpleType
import dev.cel.compiler.CelCompiler
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.CelEvaluationException
import dev.cel.runtime.CelRuntime
import dev.cel.runtime.CelRuntimeFactory
import java.util.Collections

/**
 * Administrator-written expressions in [CEL](https://cel.dev): conditions (action visibility,
 * validation rules, lifecycle gates) and formulas (calculated attributes). CEL has no side effects,
 * no loops beyond bounded macros and no I/O, so an expression can't change data, call out or hang.
 *
 * Expressions see the variables of the [ExpressionModel] (`object`, `user`); see
 * docs/EXTENSIBILITY.md for what they contain. Compiled programs are cached by source text.
 */
object Expressions {
    /** Longer expressions are refused: they belong in an extension's code (Phase 5). */
    const val MAX_LENGTH = 2_000

    /** Each `all`/`exists`/`map`/`filter` may loop at most this often. */
    private const val MAX_ITERATIONS = 1_000
    private const val CACHE_SIZE = 500

    private val options =
        CelOptions
            .current()
            .maxExpressionCodePointSize(MAX_LENGTH)
            .comprehensionMaxIterations(MAX_ITERATIONS)
            .build()

    private fun compiler(kind: ExpressionKind): CelCompiler =
        CelCompilerFactory
            .standardCelCompilerBuilder()
            .setOptions(options)
            .apply { ExpressionModel.VARIABLES.forEach { addVar(it, SimpleType.DYN) } }
            .setResultType(if (kind == ExpressionKind.CONDITION) SimpleType.BOOL else SimpleType.DYN)
            .build()

    private val compilers = ExpressionKind.entries.associateWith(::compiler)
    private val runtime: CelRuntime = CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(options).build()

    private val cache: MutableMap<Pair<ExpressionKind, String>, CelRuntime.Program> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Pair<ExpressionKind, String>, CelRuntime.Program>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Pair<ExpressionKind, String>, CelRuntime.Program>,
                ) = size > CACHE_SIZE
            },
        )

    /** Problems with [source] (syntax, unknown functions, a condition that isn't true/false); empty if it's fine. */
    fun check(
        source: String,
        kind: ExpressionKind,
    ): List<ExpressionProblem> {
        if (source.isBlank()) return listOf(ExpressionProblem("The expression is empty"))
        if (source.length >
            MAX_LENGTH
        ) {
            return listOf(ExpressionProblem("Expressions are limited to $MAX_LENGTH characters"))
        }
        val result = compilers.getValue(kind).compile(source)
        return result.errors.map {
            val at = it.sourceLocation
            ExpressionProblem(it.message, at.line.takeIf { l -> l > 0 }, at.column.takeIf { c -> c >= 0 }?.plus(1))
        }
    }

    /**
     * Evaluates [source] with [variables] (plain maps, lists, strings, numbers, booleans and nulls).
     *
     * @throws ExpressionException when it doesn't compile or fails at run time (a missing key, a
     *   division by zero, a type mismatch).
     */
    fun evaluate(
        source: String,
        kind: ExpressionKind,
        variables: Map<String, Any?>,
    ): Any? {
        val program = program(source, kind)
        val result =
            try {
                program.eval(variables.mapValues { (_, v) -> ExpressionValues.toCel(v) })
            } catch (e: CelEvaluationException) {
                throw ExpressionException(source, e.message ?: "Evaluation failed")
            }
        return ExpressionValues.fromCel(result)
    }

    /** A condition's verdict. */
    fun test(
        source: String,
        variables: Map<String, Any?>,
    ): Boolean =
        when (val result = evaluate(source, ExpressionKind.CONDITION, variables)) {
            is Boolean -> result
            else -> throw ExpressionException(source, "The condition must be true or false, not ${result ?: "null"}")
        }

    private fun program(
        source: String,
        kind: ExpressionKind,
    ): CelRuntime.Program =
        cache[kind to source] ?: run {
            val problems = check(source, kind)
            if (problems.isNotEmpty()) throw ExpressionException(source, problems.joinToString("; ") { it.message })
            val ast =
                try {
                    compilers.getValue(kind).compile(source).ast
                } catch (e: CelValidationException) {
                    throw ExpressionException(source, e.message ?: "Invalid expression")
                }
            runtime.createProgram(ast).also { cache[kind to source] = it }
        }
}

enum class ExpressionKind {
    /** Must produce true or false. */
    CONDITION,

    /** Any value: a formula. */
    VALUE,
}

data class ExpressionProblem(
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
)

class ExpressionException(
    val source: String,
    message: String,
) : RuntimeException(message)
