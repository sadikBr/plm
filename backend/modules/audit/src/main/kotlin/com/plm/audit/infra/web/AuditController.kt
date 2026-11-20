package com.plm.audit.infra.web

import com.plm.audit.api.AuditService
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
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
}
