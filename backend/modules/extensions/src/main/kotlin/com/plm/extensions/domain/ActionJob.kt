package com.plm.extensions.domain

import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobPermanentFailure
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Runs a background extension action (roadmap 3.11) as the person who started it. Its result keeps
 * the service's answer (message, link, download) for the page that follows the job, and its
 * `message` and `link` go into the notification sent when it ends.
 */
@Component
internal class ActionJob(
    private val service: ObjectProvider<DefaultActionInvocationService>,
) : JobHandler {
    override val type = TYPE
    override val label = "Extension action"
    override val description = "An extension's long-running action, started from an object"
    override val adminOnly = false

    // The service may have done its work: never call it twice on its own.
    override val maxAttempts = 1

    override fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) {
        if (payload["objectId"] == null || payload["actionId"] == null) {
            throw ValidationException("INVALID_JOB", "objectId and actionId are needed")
        }
    }

    override fun run(context: JobContext): Map<String, Any?> {
        val result = service.getObject().runInBackground(context)
        if (!result.ok) throw JobPermanentFailure(result.message ?: "The action failed")
        val objectId = context.payload["objectId"]
        return mapOf(
            "message" to result.message,
            "level" to result.level,
            "link" to "/objects/${result.navigateToObject ?: objectId}",
            "navigateToUrl" to result.navigateToUrl,
            "download" to
                result.download?.let {
                    mapOf(
                        "filename" to it.filename,
                        "contentType" to it.contentType,
                        "content" to it.content,
                    )
                },
        )
    }

    companion object {
        const val TYPE = "extensions.action"
    }
}
