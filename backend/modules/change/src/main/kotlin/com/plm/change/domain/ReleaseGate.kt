package com.plm.change.domain

import com.plm.change.api.ReleaseGate
import com.plm.change.api.ReleaseGateService
import com.plm.change.api.SetReleaseGate
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
import com.plm.types.api.BaseClass
import com.plm.types.api.TypeService
import com.plm.versioning.api.GuardedOperation
import com.plm.versioning.api.OperationCheck
import com.plm.versioning.api.OperationGuard
import com.plm.versioning.api.Refusal
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock

/**
 * Who is releasing right now, on this thread: set only while an approved change notice or
 * promotion request moves its objects (roadmap 5.13).
 */
internal object ReleaseAuthority {
    private val current = ThreadLocal<String?>()

    fun <T> by(
        authority: String,
        block: () -> T,
    ): T {
        val before = current.get()
        current.set(authority)
        try {
            return block()
        } finally {
            current.set(before)
        }
    }

    fun get(): String? = current.get()
}

@Service
@Transactional
internal class DefaultReleaseGateService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : ReleaseGateService {
    private val json = JsonMapper.builder().build()

    @Transactional(readOnly = true)
    override fun get(actor: Actor): ReleaseGate =
        jdbc
            .sql("select * from release_gate where tenant_id = :t")
            .param("t", actor.tenant.value)
            .query { rs, _ ->
                ReleaseGate(
                    rs.getBoolean("enabled"),
                    json.readValue(rs.getString("states"), Array<String>::class.java).toList(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("updated_by"),
                )
            }.optional()
            .orElse(ReleaseGate(true, listOf("RELEASED"), null, null))

    override fun set(
        actor: Actor,
        request: SetReleaseGate,
    ): ReleaseGate {
        if (!actor.siteAdmin) throw ForbiddenException("ADMIN_REQUIRED", "The release gate is for site administrators")
        val states =
            request.states
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .distinct()
        if (request.enabled && states.isEmpty()) {
            throw ValidationException("INVALID_RELEASE_GATE", "Name at least one state to gate")
        }
        jdbc
            .sql(
                """
                insert into release_gate (tenant_id, enabled, states, updated_at, updated_by)
                values (:t, :enabled, cast(:states as jsonb), :at, :by)
                on conflict (tenant_id) do update set enabled = excluded.enabled, states = excluded.states,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("t", actor.tenant.value)
            .param("enabled", request.enabled)
            .param("states", json.writeValueAsString(states))
            .param("at", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return get(actor)
    }
}

/** Refuses to move a part or document to a gated state, except for a change notice or promotion. */
@Component
internal class ReleaseGateGuard(
    private val gate: DefaultReleaseGateService,
    private val types: TypeService,
) : OperationGuard {
    override fun check(
        actor: Actor,
        check: OperationCheck,
    ): List<Refusal> {
        if (check.operation != GuardedOperation.SET_STATE) return emptyList()
        val to = check.event["to"]?.toString() ?: return emptyList()
        if (ReleaseAuthority.get() != null) return emptyList()
        val settings = gate.get(actor)
        if (!settings.enabled || to !in settings.states) return emptyList()
        val base = types.get(actor, check.typeId).baseClass
        if (base != BaseClass.PART && base != BaseClass.DOCUMENT) return emptyList()
        return listOf(
            Refusal(
                "change.release-gate",
                "site",
                "Objects reach $to only through an approved change notice or promotion request",
            ),
        )
    }
}
