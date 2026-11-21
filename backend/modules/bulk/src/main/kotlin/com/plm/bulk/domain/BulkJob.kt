package com.plm.bulk.domain

import com.plm.bulk.api.BulkItemResult
import com.plm.bulk.api.BulkOperation
import com.plm.bulk.api.BulkRequest
import com.plm.bulk.api.BulkService
import com.plm.jobs.api.Job
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobService
import com.plm.jobs.api.SubmitJob
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.MoveObject
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

private const val MAX_OBJECTS = 1000

@Service
internal class DefaultBulkService(
    private val jobs: JobService,
) : BulkService {
    override fun start(
        actor: Actor,
        request: BulkRequest,
    ): Job = jobs.submit(actor, SubmitJob(BulkJob.TYPE, BulkJob.payload(request)))
}

/** Runs a [BulkRequest]: object by object, each in its own transaction. */
@Component
internal class BulkJob(
    private val versioning: VersioningService,
    private val organizing: OrganizingService,
    private val tx: TransactionTemplate,
) : JobHandler {
    override val type = TYPE
    override val label = "Mass operation"
    override val description = "One operation (set state, move, delete, change a value) on many objects"
    override val adminOnly = false

    // Re-running would repeat what already succeeded: one attempt, results per object instead.
    override val maxAttempts = 1

    override fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) {
        val r = request(payload)

        fun bad(message: String): Nothing = throw ValidationException("INVALID_BULK_REQUEST", message)
        if (r.objectIds.isEmpty()) bad("Choose at least one object")
        if (r.objectIds.size > MAX_OBJECTS) bad("At most $MAX_OBJECTS objects at once")
        when (r.operation) {
            BulkOperation.SET_STATE -> if (r.state.isNullOrBlank()) bad("Which state?")
            BulkOperation.MOVE -> if (r.contextId == null) bad("Where to?")
            BulkOperation.SET_ATTRIBUTE -> if (r.attribute.isNullOrBlank()) bad("Which attribute?")
            BulkOperation.DELETE -> Unit
        }
    }

    override fun title(payload: Map<String, Any?>): String {
        val r = request(payload)
        val n = r.objectIds.size
        val what =
            when (r.operation) {
                BulkOperation.SET_STATE -> "Set state to ${r.state}"
                BulkOperation.MOVE -> "Move"
                BulkOperation.DELETE -> "Delete"
                BulkOperation.SET_ATTRIBUTE -> "Set ${r.attribute}"
            }
        return "$what: $n object${if (n == 1) "" else "s"}"
    }

    override fun run(context: JobContext): Map<String, Any?> {
        val r = request(context.payload)
        val actor = context.actor
        val results = mutableListOf<BulkItemResult>()
        r.objectIds.forEachIndexed { i, id ->
            context.checkCancelled()
            val master = MasterId(id)
            val number = runCatching { versioning.get(actor, master).number }.getOrNull()
            val result =
                try {
                    val message = tx.execute { apply(actor, master, r) }
                    BulkItemResult(id, number, true, message)
                } catch (e: PlmException) {
                    BulkItemResult(id, number, false, e.message ?: e.code)
                } catch (e: Exception) {
                    BulkItemResult(id, number, false, e.message ?: e.javaClass.simpleName)
                }
            results += result
            if (!result.ok) context.log("${number ?: id}: ${result.message}", "WARN")
            context.progress(
                i + 1,
                r.objectIds.size,
                "${results.count { it.ok }} done, ${results.count { !it.ok }} failed",
            )
        }
        return mapOf(
            "operation" to r.operation.name,
            "succeeded" to results.count { it.ok },
            "failed" to results.count { !it.ok },
            "results" to
                results.map {
                    mapOf(
                        "objectId" to it.objectId.toString(),
                        "number" to it.number,
                        "ok" to it.ok,
                        "message" to it.message,
                    )
                },
        )
    }

    private fun apply(
        actor: Actor,
        id: MasterId,
        r: BulkRequest,
    ): String =
        when (r.operation) {
            BulkOperation.SET_STATE -> {
                val obj = versioning.setState(actor, id, SetState(r.state!!, r.comment))
                "Now ${obj.latest.state}"
            }
            BulkOperation.MOVE -> {
                organizing.move(actor, id, MoveObject(r.contextId!!, r.folderId))
                "Moved"
            }
            BulkOperation.DELETE -> {
                organizing.delete(actor, id)
                "Deleted"
            }
            BulkOperation.SET_ATTRIBUTE -> {
                val wc = versioning.checkOut(actor, id)
                versioning.updateWorkingCopy(actor, id, wc.attributes + (r.attribute!! to r.value))
                val obj = versioning.checkIn(actor, id, r.comment ?: "Mass change: ${r.attribute}")
                "Now ${obj.latest.version}"
            }
        }

    companion object {
        const val TYPE = "bulk.operation"
        private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        @Suppress("UNCHECKED_CAST")
        fun payload(r: BulkRequest): Map<String, Any?> = json.convertValue(r, Map::class.java) as Map<String, Any?>

        fun request(payload: Map<String, Any?>): BulkRequest =
            try {
                json.convertValue(payload, BulkRequest::class.java)
            } catch (e: Exception) {
                throw ValidationException("INVALID_BULK_REQUEST", "Not a mass operation: ${e.message?.take(200)}")
            }
    }
}
