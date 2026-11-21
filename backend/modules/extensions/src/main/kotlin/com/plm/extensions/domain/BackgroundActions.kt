package com.plm.extensions.domain

import com.plm.jobs.api.JobContext
import com.plm.platform.error.NotFoundException
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Background actions that are running here (roadmap 3.11), so their service can report back: each
 * gets a one-off token, sent in the call and checked (by hash) on every progress or completion
 * report. Lives in memory: the job and its waiting thread are on this node.
 */
@Component
internal class BackgroundActions {
    class Running(
        val tokenHash: ByteArray,
        val context: JobContext,
        val completion: CompletableFuture<ByteArray> = CompletableFuture(),
    ) {
        @Volatile var progress: Triple<Int, Int?, String?>? = null
    }

    private val running = ConcurrentHashMap<UUID, Running>()
    private val random = SecureRandom()

    /** Registers the job; returns its token. */
    fun start(context: JobContext): Pair<String, Running> {
        val bytes = ByteArray(24).also(random::nextBytes)
        val token = "pjt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val entry = Running(hash(token), context)
        running[context.jobId] = entry
        return token to entry
    }

    fun end(jobId: UUID) {
        running.remove(jobId)
    }

    /** The running job [token] belongs to; not found otherwise (never says which part was wrong). */
    fun find(
        jobId: UUID,
        token: String?,
    ): Running {
        val entry = running[jobId]
        if (entry == null || token == null || !MessageDigest.isEqual(entry.tokenHash, hash(token))) {
            throw NotFoundException("JOB_NOT_RUNNING", "No running background action with that id and token")
        }
        return entry
    }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
}
