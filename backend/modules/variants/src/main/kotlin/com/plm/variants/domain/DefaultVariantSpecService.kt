package com.plm.variants.domain

import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.Permission
import com.plm.bom.api.ConfigSpec
import com.plm.bom.api.ConfigSpecType
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.variants.api.ConfiguredStructure
import com.plm.variants.api.SaveVariantSpec
import com.plm.variants.api.SelectionCheck
import com.plm.variants.api.VariantSpec
import com.plm.variants.api.VariantSpecService
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** A selection that breaks the rules, saying what's wrong. */
internal fun invalidSelection(check: SelectionCheck): Nothing =
    throw ValidationException("INVALID_SELECTION", check.problems.joinToString("; ") { it.message })

@Service
@Transactional
internal class DefaultVariantSpecService(
    private val jdbc: JdbcClient,
    private val options: DefaultOptionService,
    private val rules: DefaultOptionRuleService,
    private val conditions: DefaultConditionService,
    private val versioning: VersioningService,
    private val access: AccessService,
    private val clock: Clock,
) : VariantSpecService {
    private val json = JsonMapper.builder().build()
    private val mapType = object : TypeReference<Map<String, String>>() {}

    private data class Row(
        val id: UUID,
        val productId: UUID,
        val name: String,
        val description: String?,
        val selection: Map<String, String>,
        val spec: ConfigSpec,
        val createdAt: java.time.Instant,
        val createdBy: String,
        val updatedAt: java.time.Instant,
        val updatedBy: String,
    )

    private fun row(rs: ResultSet) =
        Row(
            rs.getObject("id", UUID::class.java),
            rs.getObject("product_id", UUID::class.java),
            rs.getString("name"),
            rs.getString("description"),
            json.readValue(rs.getString("selection"), mapType),
            ConfigSpec(
                ConfigSpecType.valueOf(rs.getString("spec_type")),
                state = rs.getString("spec_state"),
                effectivityKind = rs.getString("eff_kind"),
                effectiveAt = rs.getString("eff_at"),
                endItemId = rs.getObject("end_item_id", UUID::class.java),
            ),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("created_by"),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getString("updated_by"),
        )

    private fun rows(
        where: String,
        params: Map<String, Any?>,
    ) = jdbc
        .sql("select * from variant_spec where $where order by name")
        .params(params)
        .query { rs, _ -> row(rs) }
        .list()

    private fun configurable(
        actor: Actor,
        productId: UUID,
    ) = options.setOf(productId)?.let { options.get(actor, it) }
        ?: throw ValidationException(
            "NOT_CONFIGURABLE",
            "${versioning.get(actor, MasterId(productId)).number} isn't configurable",
        )

    private fun view(
        actor: Actor,
        r: Row,
    ): VariantSpec {
        val set = configurable(actor, r.productId)
        return VariantSpec(
            r.id,
            r.productId,
            r.name,
            r.description,
            r.selection,
            r.spec,
            rules.check(set, r.selection, allowRetired = true),
            r.createdAt,
            r.createdBy,
            r.updatedAt,
            r.updatedBy,
        )
    }

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        productId: UUID,
    ): List<VariantSpec> {
        versioning.get(actor, MasterId(productId))
        if (options.setOf(productId) == null) return emptyList()
        return rows("product_id = :p", mapOf("p" to productId)).map { view(actor, it) }
    }

    private fun find(
        actor: Actor,
        specId: UUID,
    ): Row {
        val r =
            rows("id = :id and tenant_id = :t", mapOf("id" to specId, "t" to actor.tenant.value)).singleOrNull()
                ?: throw NotFoundException("VARIANT_SPEC_NOT_FOUND", "There's no such variant specification")
        versioning.get(actor, MasterId(r.productId))
        return r
    }

    @Transactional(readOnly = true)
    override fun get(
        actor: Actor,
        specId: UUID,
    ) = view(actor, find(actor, specId))

    /** Checks the request against the product: modifiable, configurable, and a selection keeping its rules. */
    private fun validate(
        actor: Actor,
        productId: UUID,
        request: SaveVariantSpec,
    ): Map<String, String> {
        val o = versioning.get(actor, MasterId(productId))
        access.require(actor, AccessTarget(o.contextId, o.typeId, o.latest.state, o.id.value), Permission.MODIFY)
        val set = configurable(actor, productId)
        if (request.name.isBlank() ||
            request.name.length > 200
        ) {
            throw ValidationException("INVALID_VARIANT_SPEC", "A name is 1 to 200 characters")
        }
        val selection =
            request.selection.entries
                .associate { (k, v) -> k.trim().uppercase() to v.trim().uppercase() }
                .filterValues { it.isNotEmpty() }
        val check = rules.check(set, selection)
        if (!check.valid) invalidSelection(check)
        if (request.spec.type == ConfigSpecType.BASELINE) {
            throw ValidationException(
                "INVALID_VARIANT_SPEC",
                "A variant is read as latest, in a state, as of a date or as effective",
            )
        }
        return selection
    }

    private fun duplicate(name: String): Nothing =
        throw ConflictException("DUPLICATE_NAME", "There's already a variant specification named $name")

    override fun create(
        actor: Actor,
        productId: UUID,
        request: SaveVariantSpec,
    ): VariantSpec {
        val selection = validate(actor, productId, request)
        if (rows(
                "product_id = :p and name = :n",
                mapOf("p" to productId, "n" to request.name.trim()),
            ).isNotEmpty()
        ) {
            duplicate(request.name.trim())
        }
        val id = UuidV7.generate(clock)
        val now = Timestamp.from(clock.instant())
        jdbc
            .sql(
                """
                insert into variant_spec (id, tenant_id, product_id, name, description, selection, spec_type, spec_state, eff_kind,
                    eff_at, end_item_id, created_at, created_by, updated_at, updated_by)
                values (:id, :t, :p, :n, :d, cast(:sel as jsonb), :st, :ss, :ek, :ea, :ei, :now, :by, :now, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("t", actor.tenant.value)
            .param("p", productId)
            .params(columns(request, selection))
            .param("now", now)
            .param("by", actor.principal.value)
            .update()
        return get(actor, id)
    }

    private fun columns(
        request: SaveVariantSpec,
        selection: Map<String, String>,
    ): Map<String, Any?> =
        mapOf(
            "n" to request.name.trim(),
            "d" to request.description?.trim()?.takeIf { it.isNotEmpty() },
            "sel" to json.writeValueAsString(selection),
            "st" to request.spec.type.name,
            "ss" to request.spec.state,
            "ek" to request.spec.effectivityKind,
            "ea" to request.spec.effectiveAt,
            "ei" to request.spec.endItemId,
        )

    override fun update(
        actor: Actor,
        specId: UUID,
        request: SaveVariantSpec,
    ): VariantSpec {
        val r = find(actor, specId)
        val selection = validate(actor, r.productId, request)
        val clash =
            rows(
                "product_id = :p and name = :n and id <> :id",
                mapOf(
                    "p" to r.productId,
                    "n" to request.name.trim(),
                    "id" to specId,
                ),
            )
        if (clash.isNotEmpty()) duplicate(request.name.trim())
        jdbc
            .sql(
                """
                update variant_spec set name = :n, description = :d, selection = cast(:sel as jsonb), spec_type = :st, spec_state = :ss,
                    eff_kind = :ek, eff_at = :ea, end_item_id = :ei, updated_at = :now, updated_by = :by where id = :id
                """.trimIndent(),
            ).param("id", specId)
            .params(columns(request, selection))
            .param("now", Timestamp.from(clock.instant()))
            .param("by", actor.principal.value)
            .update()
        return get(actor, specId)
    }

    override fun delete(
        actor: Actor,
        specId: UUID,
    ) {
        val r = find(actor, specId)
        val o = versioning.get(actor, MasterId(r.productId))
        access.require(actor, AccessTarget(o.contextId, o.typeId, o.latest.state, o.id.value), Permission.MODIFY)
        jdbc.sql("delete from variant_spec where id = :id").param("id", specId).update()
    }

    @Transactional(readOnly = true)
    override fun structure(
        actor: Actor,
        specId: UUID,
    ): ConfiguredStructure {
        val r = find(actor, specId)
        return conditions.configured(actor, r.productId, r.selection, r.spec)
    }
}
