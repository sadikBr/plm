package com.plm.extensions.infra.web

import com.plm.extensions.domain.BackgroundActions
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ActionProgress(
    val done: Int,
    val total: Int? = null,
    val message: String? = null,
)

data class ActionProgressReply(
    /** Someone cancelled the job: the service should stop. */
    val cancelRequested: Boolean,
)

/**
 * Where a background action's service reports back (roadmap 3.11), authenticated by the job's own
 * token in `X-PLM-Job-Token` (not a user's).
 */
@RestController
internal class BackgroundActionController(
    private val background: BackgroundActions,
) {
    @PostMapping("/api/v1/extension-jobs/{jobId}/progress")
    fun progress(
        @PathVariable jobId: UUID,
        @RequestHeader("X-PLM-Job-Token", required = false) token: String?,
        @RequestBody progress: ActionProgress,
    ): ActionProgressReply {
        val running = background.find(jobId, token)
        val total = progress.total?.coerceAtLeast(0)
        val done = progress.done.coerceIn(0, total ?: Int.MAX_VALUE)
        val message = progress.message?.take(300)
        running.progress = Triple(done, total, message)
        running.context.progress(done, total, message)
        if (message != null) running.context.log(message)
        return ActionProgressReply(running.context.cancelRequested)
    }

    /** The final answer, for a service that answered 202 at first: the same body as a direct answer. */
    @PostMapping("/api/v1/extension-jobs/{jobId}/complete", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun complete(
        @PathVariable jobId: UUID,
        @RequestHeader("X-PLM-Job-Token", required = false) token: String?,
        @RequestBody answer: ByteArray,
    ) {
        background.find(jobId, token).completion.complete(answer)
    }
}
