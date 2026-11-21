package com.plm.bom.domain

import com.plm.bom.api.BomService
import com.plm.bom.api.MassReplace
import com.plm.bom.api.StructureOperationsService
import com.plm.jobs.api.Job
import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import com.plm.jobs.api.JobService
import com.plm.jobs.api.SubmitJob
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

private const val MAX_PARENTS = 500

@Service
internal class DefaultStructureOperationsService(
    private val jobs: JobService,
) : StructureOperationsService {
    override fun replaceEverywhere(
        actor: Actor,
        request: MassReplace,
    ): Job = jobs.submit(actor, SubmitJob(MassReplaceJob.TYPE, MassReplaceJob.payload(request)))
}

/** Mass replace across where-used (roadmap 4.14): parent by parent, each in its own transaction. */
@Component
internal class MassReplaceJob(
    private val versioning: VersioningService,
    private val bom: BomService,
    private val tx: TransactionTemplate,
) : JobHandler {
    override val type = TYPE
    override val label = "Mass replace"
    override val description = "Puts one part in place of another in the structure of many parts"
    override val adminOnly = false
    override val maxAttempts = 1

    override fun validate(
        actor: Actor,
        payload: Map<String, Any?>,
    ) {
        val r = request(payload)

        fun bad(message: String): Nothing = throw ValidationException("INVALID_MASS_REPLACE", message)
        if (r.oldId == r.newId) bad("Choose another part to put in its place")
        if (r.parentIds.isEmpty()) bad("Choose at least one part where it's used")
        if (r.parentIds.size > MAX_PARENTS) bad("At most $MAX_PARENTS parts at once")
        // Both must be readable (404 otherwise).
        versioning.get(actor, MasterId(r.oldId))
        versioning.get(actor, MasterId(r.newId))
    }

    override fun title(payload: Map<String, Any?>): String {
        val n = request(payload).parentIds.size
        return "Replace a part in $n structure${if (n == 1) "" else "s"}"
    }

    override fun run(context: JobContext): Map<String, Any?> {
        val r = request(context.payload)
        val actor = context.actor
        val old = versioning.get(actor, MasterId(r.oldId))
        val new = versioning.get(actor, MasterId(r.newId))
        context.log("Replacing ${old.number} by ${new.number}", "INFO")
        val results = mutableListOf<Map<String, Any?>>()
        r.parentIds.distinct().forEachIndexed { i, id ->
            context.checkCancelled()
            val number = runCatching { versioning.get(actor, MasterId(id)).number }.getOrNull()
            val (ok, message) =
                try {
                    true to tx.execute { replaceIn(actor, MasterId(id), r, old.number, new.number) }
                } catch (e: PlmException) {
                    false to (e.message ?: e.code)
                }
            results += mapOf("objectId" to id.toString(), "number" to number, "ok" to ok, "message" to message)
            if (!ok) context.log("${number ?: id}: $message", "WARN")
            context.progress(
                i + 1,
                r.parentIds.size,
                "${results.count { it["ok"] == true }} done, ${results.count { it["ok"] == false }} failed",
            )
        }
        return mapOf(
            "succeeded" to results.count { it["ok"] == true },
            "failed" to results.count { it["ok"] == false },
            "results" to results,
        )
    }

    private fun replaceIn(
        actor: Actor,
        parent: MasterId,
        r: MassReplace,
        oldNumber: String,
        newNumber: String,
    ): String {
        val obj = versioning.get(actor, parent)
        val mine = obj.checkedOutBy == actor.principal.value
        if (obj.checkedOutBy != null && !mine) throw ConflictException("CHECKED_OUT", "Checked out by someone else")
        var revised = false
        if (!mine && !versioning.lifecycle(actor, parent).editable) {
            if (!r.reviseReleased) {
                throw ValidationException(
                    "NOT_EDITABLE",
                    "It's ${obj.latest.state}: not changed (choose to revise such parts)",
                )
            }
            versioning.revise(actor, parent)
            revised = true
        }
        if (!mine) versioning.checkOut(actor, parent)
        val lines = bom.usages(actor, parent, workingCopy = true).filter { it.child.id == r.oldId }
        // Throwing rolls the whole parent back, revision and check-out included.
        if (lines.isEmpty()) throw ValidationException("NOT_USED", "It doesn't use $oldNumber (any more)")
        lines.forEach { bom.replace(actor, parent, it.id, r.newId) }
        val what = "$oldNumber → $newNumber on ${lines.size} line${if (lines.size == 1) "" else "s"}"
        if (mine) return "$what, in your working copy"
        val done = versioning.checkIn(actor, parent, r.comment ?: "Replaced $oldNumber by $newNumber")
        return "$what${if (revised) ", revised" else ""} (now ${done.latest.version})"
    }

    companion object {
        const val TYPE = "bom.mass-replace"
        private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MassReplace): Map<String, Any?> = json.convertValue(r, Map::class.java) as Map<String, Any?>

        fun request(payload: Map<String, Any?>): MassReplace =
            try {
                json.convertValue(payload, MassReplace::class.java)
            } catch (e: Exception) {
                throw ValidationException("INVALID_MASS_REPLACE", "Not a mass replace: ${e.message?.take(200)}")
            }
    }
}
