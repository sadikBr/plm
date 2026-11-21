package com.plm.notifications.domain

import com.plm.jobs.api.JobStatus
import com.plm.platform.actor.Actor
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.VersioningService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

private val log = LoggerFactory.getLogger(SubscriptionNotices::class.java)

/**
 * Turns changes into notifications (roadmap 3.9): for everyone subscribed to the object, its folder
 * or its context who may still read it (never the person who made the change), and for whoever
 * submitted a background job when it ends. Runs in the change's transaction, so a notice exists
 * exactly when the change does.
 */
@Component
internal class SubscriptionNotices(
    private val jdbc: JdbcClient,
    private val versioning: VersioningService,
    private val personal: DefaultPersonalService,
) {
    @EventListener(EventEnvelope::class)
    fun on(event: EventEnvelope) {
        try {
            when {
                event.eventType == "jobs.JobFinished.v1" -> jobFinished(event)
                event.eventType == "versioning.ObjectShared.v1" -> shared(event)
                event.eventType.startsWith("versioning.") -> objectChanged(event)
            }
        } catch (e: Exception) {
            log.warn("Couldn't notify for {} ({}): {}", event.id, event.eventType, e.toString())
        }
    }

    private fun jobFinished(event: EventEnvelope) {
        val p = event.payload
        val who = p["submittedBy"]?.toString() ?: return
        if (who == "system" || who.startsWith("svc-")) return
        val status = p["status"]?.toString() ?: return
        val title = p["title"]?.toString() ?: "Background job"
        val text =
            when (status) {
                JobStatus.SUCCEEDED.name -> "Finished: $title"
                JobStatus.CANCELLED.name -> "Cancelled: $title"
                else -> "Failed: $title"
            }
        personal.notify(TenantId(event.tenantId), who, text, p["error"]?.toString(), null, actor = "system")
    }

    private fun shared(event: EventEnvelope) {
        val p = event.payload
        val who = p["principalId"]?.toString() ?: return
        val tenant = TenantId(event.tenantId)
        val objectId = UUID.fromString(p["objectId"]?.toString() ?: return)
        val system = Actor(tenant, PrincipalId("system"), siteAdmin = true)
        val obj =
            versioning.list(system, ObjectQuery(ids = listOf(MasterId(objectId)), limit = 1)).firstOrNull() ?: return
        val modify = (p["permissions"] as? List<*>)?.contains("MODIFY") == true
        personal.notify(
            tenant,
            who,
            "${personal.nameOf(tenant, event.actor)} shared ${obj.number} ${obj.name} with you",
            if (modify) "You can view and edit it" else "You can view it",
            "/objects/$objectId",
            objectId,
            event.actor,
        )
    }

    private fun objectChanged(event: EventEnvelope) {
        val p = event.payload
        val what = describe(event.eventType, p) ?: return
        val tenant = TenantId(event.tenantId)
        val objectId = UUID.fromString(p["objectId"]?.toString() ?: return)
        val contextId = UUID.fromString(p["contextId"]?.toString() ?: return)
        val deleted = event.eventType == "versioning.ObjectDeleted.v1"
        val system = Actor(tenant, PrincipalId("system"), siteAdmin = true)
        val current =
            if (deleted) {
                null
            } else {
                versioning
                    .list(
                        system,
                        ObjectQuery(ids = listOf(MasterId(objectId)), limit = 1),
                    ).firstOrNull()
            }
        val folderIds =
            buildSet {
                current?.folderId?.let(::add)
                p["fromFolderId"]?.toString()?.let { add(UUID.fromString(it)) }
            }
        val subscribers =
            jdbc
                .sql(
                    """
                    select distinct principal_id, bool_or(site_admin) as site_admin from subscription
                    where tenant_id = :tenant and principal_id <> :actor and (
                        (kind = 'OBJECT' and target_id = :object) or (kind = 'CONTEXT' and target_id = :context)
                        or (kind = 'FOLDER' and target_id = any(cast(:folders as uuid[]))))
                    group by principal_id
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("actor", event.actor)
                .param("object", objectId)
                .param("context", contextId)
                .param("folders", folderIds.toTypedArray())
                .query { rs, _ -> rs.getString("principal_id") to rs.getBoolean("site_admin") }
                .list()
        if (subscribers.isEmpty()) return
        val number = p["number"]?.toString() ?: ""
        val name = p["name"]?.toString() ?: current?.name
        val by = personal.nameOf(tenant, event.actor)
        for ((principal, admin) in subscribers) {
            // Only those who may (still) read it; a deleted object can't be checked any more.
            if (!deleted) {
                val them = Actor(tenant, PrincipalId(principal), siteAdmin = admin)
                if (versioning.list(them, ObjectQuery(ids = listOf(MasterId(objectId)), limit = 1)).isEmpty()) continue
            }
            val label = listOfNotNull(number, name).joinToString(" ")
            personal.notify(
                tenant,
                principal,
                "$label $what",
                "By $by",
                if (deleted) null else "/objects/$objectId",
                objectId,
                event.actor,
            )
        }
        if (deleted) {
            jdbc
                .sql("delete from subscription where tenant_id = :tenant and kind = 'OBJECT' and target_id = :object")
                .param("tenant", tenant.value)
                .param("object", objectId)
                .update()
        }
    }

    private fun describe(
        type: String,
        p: Map<String, Any?>,
    ): String? =
        when (type) {
            "versioning.ObjectCreated.v1" -> "was created"
            "versioning.ObjectCheckedOut.v1" -> "was checked out"
            "versioning.ObjectCheckedIn.v1" ->
                "was checked in as ${p["version"]}" +
                    (p["comment"]?.let { ": $it" } ?: "")
            "versioning.ObjectRevised.v1" -> "was revised to ${p["version"]}"
            "versioning.ObjectStateChanged.v1" -> "moved to ${p["to"]?.toString()?.let(::stateLabel)}"
            "versioning.ObjectRenamed.v1" -> "was renamed (was ${p["previousNumber"]} ${p["previousName"]})"
            "versioning.ObjectLifecycleChanged.v1" -> "now follows ${p["lifecycle"]}"
            "versioning.ObjectMoved.v1" -> "was moved"
            "versioning.ObjectDeleted.v1" -> "was deleted"
            else -> null
        }

    private fun stateLabel(s: String) =
        s.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
