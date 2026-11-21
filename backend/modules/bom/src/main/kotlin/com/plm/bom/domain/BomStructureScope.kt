package com.plm.bom.domain

import com.plm.bom.api.BomService
import com.plm.bom.api.ConfigSpec
import com.plm.platform.actor.Actor
import com.plm.search.api.StructureScope
import com.plm.versioning.api.MasterId
import org.springframework.stereotype.Component
import java.util.UUID

/** Searches and reports limited to a structure (roadmap 4.11): the parts under the latest of it. */
@Component
internal class BomStructureScope(
    private val bom: BomService,
) : StructureScope {
    override fun partsUnder(
        actor: Actor,
        partId: UUID,
    ): List<UUID> =
        bom
            .structure(actor, MasterId(partId), ConfigSpec.LATEST, depth = 50, limit = 50_000)
            .nodes
            .filter { it.line.child.readable }
            .map { it.line.child.id }
            .distinct()
}
