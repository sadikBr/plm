package com.plm.audit.infra.web

import com.plm.audit.api.AuditQuery
import com.plm.audit.api.AuditService
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/audit")
internal class AuditController(
    private val audit: AuditService,
) {
    /** `?aggregateId=` narrows to one object/context. `?before=<seq>` pages backwards. */
    @GetMapping
    fun list(
        actor: Actor,
        @RequestParam(required = false) aggregateId: UUID?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) before: Long?,
    ) = if (aggregateId != null) {
        audit.forAggregate(actor, aggregateId, limit, before)
    } else {
        audit.recent(actor, limit, before)
    }

    /** An audit report (roadmap 3.10). */
    @GetMapping("/report")
    fun report(
        actor: Actor,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false, name = "actor") who: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) contextId: UUID?,
        @RequestParam(required = false) aggregateId: UUID?,
        @RequestParam(defaultValue = "200") limit: Int,
        @RequestParam(required = false) before: Long?,
    ) = audit.report(actor, AuditQuery(from, to, who, action, contextId, aggregateId, limit, before))
}
