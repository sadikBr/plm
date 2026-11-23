package com.plm.versioning.infra.web

import com.plm.access.api.AccessTarget
import com.plm.access.api.LabelExplanation
import com.plm.access.api.ObjectLabel
import com.plm.access.api.SecurityLabelService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ObjectLabelValues(
    /** Label id → value key; null removes the label. */
    val values: Map<UUID, String?>,
)

/** An object's security labels, and why someone can or can't see it (roadmap 9.7). */
@RestController
internal class ObjectSecurityController(
    private val versioning: VersioningService,
    private val labels: SecurityLabelService,
) {
    @GetMapping("/api/v1/objects/{id}/security-labels")
    fun get(
        actor: Actor,
        @PathVariable id: UUID,
    ) = versioning.get(actor, MasterId(id)).let { labels.objectLabels(id) }

    @PutMapping("/api/v1/objects/{id}/security-labels")
    fun set(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody body: ObjectLabelValues,
    ): List<ObjectLabel> {
        val o = versioning.get(actor, MasterId(id))
        return labels.setObjectLabels(actor, AccessTarget(o.contextId, o.typeId, o.latest.state, id), body.values)
    }

    /** Also for someone the labels keep out: it says which ones, and what would let them in. */
    @GetMapping("/api/v1/objects/{id}/access-explanation")
    fun explain(
        actor: Actor,
        @PathVariable id: UUID,
    ): LabelExplanation {
        val o = versioning.get(Actor(actor.tenant, PrincipalId("system"), siteAdmin = true), MasterId(id))
        return labels.explain(actor, AccessTarget(o.contextId, o.typeId, o.latest.state, id))
    }
}
