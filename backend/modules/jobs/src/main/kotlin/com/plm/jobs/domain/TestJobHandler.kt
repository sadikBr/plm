package com.plm.jobs.domain

import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import org.springframework.stereotype.Component

/**
 * A job that only waits and reports progress: to check the queue, the workers and the monitor
 * work. `steps` (1-600) steps of `stepMillis` (0-10000) each; fails its first `failTimes` attempts.
 */
@Component
internal class TestJobHandler : JobHandler {
    override val type = "system.test"
    override val label = "Test job"
    override val description = "Waits a while, reporting progress; can fail on purpose to try retries"

    override fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) {
        val steps = int(payload, "steps", 10)
        val millis = int(payload, "stepMillis", 1000)
        if (steps !in 1..600 || millis !in 0..10_000 || int(payload, "failTimes", 0) !in 0..20) {
            throw ValidationException("INVALID_JOB", "steps 1-600, stepMillis 0-10000, failTimes 0-20")
        }
    }

    override fun title(payload: Map<String, Any?>) = "Test job: ${int(payload, "steps", 10)} steps"

    override fun run(context: JobContext): Map<String, Any?> {
        val steps = int(context.payload, "steps", 10)
        val millis = int(context.payload, "stepMillis", 1000).toLong()
        val failTimes = int(context.payload, "failTimes", 0)
        for (i in 1..steps) {
            context.checkCancelled()
            if (context.attempt <= failTimes && i > steps / 2) error("Failing on purpose (attempt ${context.attempt})")
            Thread.sleep(millis)
            context.progress(i, steps, "Step $i of $steps")
        }
        context.log("All $steps steps done")
        return mapOf("steps" to steps)
    }

    private fun int(
        payload: Map<String, Any?>,
        key: String,
        default: Int,
    ): Int = (payload[key] as? Number)?.toInt() ?: (payload[key] as? String)?.toIntOrNull() ?: default
}
