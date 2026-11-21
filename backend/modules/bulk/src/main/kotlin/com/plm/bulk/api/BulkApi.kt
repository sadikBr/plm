package com.plm.bulk.api

import com.plm.jobs.api.Job
import com.plm.platform.actor.Actor
import java.util.UUID

enum class BulkOperation {
    /** [BulkRequest.state], with [BulkRequest.comment]. */
    SET_STATE,

    /** To [BulkRequest.contextId] (and [BulkRequest.folderId]). */
    MOVE,

    /** By the delete rules. */
    DELETE,

    /** [BulkRequest.attribute] to [BulkRequest.value]: checked out, changed, checked in. */
    SET_ATTRIBUTE,
}

data class BulkRequest(
    val operation: BulkOperation,
    val objectIds: List<UUID>,
    val state: String? = null,
    val comment: String? = null,
    val contextId: UUID? = null,
    val folderId: UUID? = null,
    val attribute: String? = null,
    val value: Any? = null,
)

/** One object's outcome. */
data class BulkItemResult(
    val objectId: UUID,
    val number: String?,
    val ok: Boolean,
    val message: String,
)

/**
 * Mass operations (roadmap 3.5): one operation on many objects, as a background job that runs as
 * the person who started it (their access rules, their name in the audit trail). Each object
 * succeeds or fails on its own; the job's result lists every object's outcome
 * (`results`: [BulkItemResult], `succeeded`, `failed`). At most 1000 objects.
 *
 * Error code: `INVALID_BULK_REQUEST`.
 */
interface BulkService {
    fun start(
        actor: Actor,
        request: BulkRequest,
    ): Job
}
