package com.plm.extensions.infra.web

import com.plm.platform.actor.Actor
import com.plm.platform.expression.ExpressionException
import com.plm.platform.expression.ExpressionKind
import com.plm.platform.expression.ExpressionProblem
import com.plm.platform.expression.Expressions
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Helps administrators write expressions (roadmap 2.2): check one as they type, see the variables
 * an object offers, and try an expression on a real object. Everything runs with the caller's
 * access: an object they can't read is not found.
 */
@RestController
internal class ExpressionController(
    private val versioning: VersioningService,
) {
    @PostMapping("/api/v1/expressions/check")
    fun check(
        @RequestBody request: CheckExpressionRequest,
    ) = CheckExpressionResponse(Expressions.check(request.expression, request.kind))

    /** The variables expressions see for this object (`object`, `user`). */
    @GetMapping("/api/v1/objects/{id}/expression-model")
    fun model(
        actor: Actor,
        @PathVariable id: UUID,
    ): Map<String, Any?> = versioning.expressionModel(actor, MasterId(id))

    /** Evaluates an expression on an object: its value, or why it failed. */
    @PostMapping("/api/v1/objects/{id}/expression-model/evaluate")
    fun evaluate(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: CheckExpressionRequest,
    ): EvaluateExpressionResponse {
        val problems = Expressions.check(request.expression, request.kind)
        if (problems.isNotEmpty()) return EvaluateExpressionResponse(problems = problems)
        val model = versioning.expressionModel(actor, MasterId(id))
        return try {
            val value =
                if (request.kind == ExpressionKind.CONDITION) {
                    Expressions.test(request.expression, model)
                } else {
                    Expressions.evaluate(request.expression, request.kind, model)
                }
            EvaluateExpressionResponse(value = value)
        } catch (e: ExpressionException) {
            EvaluateExpressionResponse(error = e.message)
        }
    }
}

internal data class CheckExpressionRequest(
    val expression: String,
    val kind: ExpressionKind = ExpressionKind.CONDITION,
)

internal data class CheckExpressionResponse(
    val problems: List<ExpressionProblem>,
) {
    val valid: Boolean get() = problems.isEmpty()
}

internal data class EvaluateExpressionResponse(
    val value: Any? = null,
    /** Evaluation failed at run time (a missing field, a type mismatch...). */
    val error: String? = null,
    /** It doesn't compile. */
    val problems: List<ExpressionProblem> = emptyList(),
)
