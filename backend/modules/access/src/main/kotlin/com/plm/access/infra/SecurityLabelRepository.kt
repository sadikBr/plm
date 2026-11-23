package com.plm.access.infra

import com.plm.access.api.Agreement
import com.plm.access.api.Clearance
import com.plm.access.api.LabelValue
import com.plm.access.api.ObjectLabel
import com.plm.access.api.SecurityLabel
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private val COLUMN = Regex("^[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?$")

/** A label on an object the person doesn't pass. */
internal data class Blocking(
    val labelId: UUID,
    val labelName: String,
    val valueKey: String,
    val valueRank: Int,
)

@Repository
internal class SecurityLabelRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()
    private val valuesType = object : TypeReference<List<Map<String, String>>>() {}

    private fun values(text: String): List<LabelValue> =
        json.readValue(text, valuesType).map {
            LabelValue(it.getValue("key"), it.getValue("label"))
        }

    /**
     * SQL true when the person (`:acl_tenant`, `:acl_principal`, `:acl_now`) passes every label on
     * the object in [objectColumn], whose context is [contextColumn]: cleared at that value or
     * above, or covered by an agreement naming them (for the object, or a context above it).
     */
    fun predicate(
        objectColumn: String,
        contextColumn: String,
    ): String {
        require(COLUMN.matches(objectColumn) && COLUMN.matches(contextColumn)) { "Not a plain column reference" }
        return """
            not exists (
                select 1 from object_label sl_ol
                where sl_ol.object_id = $objectColumn
                  and not exists (
                      select 1 from clearance sl_c
                      where sl_c.tenant_id = :acl_tenant and sl_c.principal_id = :acl_principal
                        and sl_c.label_id = sl_ol.label_id and sl_c.value_rank >= sl_ol.value_rank
                        and (sl_c.expires_at is null or sl_c.expires_at > :acl_now))
                  and not exists (
                      select 1 from agreement sl_a
                      join agreement_principal sl_ap on sl_ap.agreement_id = sl_a.id and sl_ap.principal_id = :acl_principal
                      join agreement_scope sl_s on sl_s.agreement_id = sl_a.id
                      where sl_a.tenant_id = :acl_tenant and sl_a.active
                        and sl_a.label_id = sl_ol.label_id and sl_a.value_rank >= sl_ol.value_rank
                        and sl_a.valid_from <= :acl_now and (sl_a.valid_to is null or sl_a.valid_to > :acl_now)
                        and (sl_s.object_id = sl_ol.object_id or exists (
                            select 1 from context_closure sl_cc
                            where sl_cc.ancestor_id = sl_s.context_id and sl_cc.descendant_id = $contextColumn)))
            )
            """.trimIndent()
    }

    /** The labels on [objectId] that [principal] doesn't pass. */
    fun blocking(
        tenant: UUID,
        principal: String,
        objectId: UUID,
        contextId: UUID,
        now: Instant,
    ): List<Blocking> =
        jdbc
            .sql(
                """
                select sl.id, sl.name,
                       coalesce((select v ->> 'label' from jsonb_array_elements(sl."values") v where v ->> 'key' = ol.value_key), ol.value_key),
                       ol.value_rank
                from object_label ol join security_label sl on sl.id = ol.label_id
                where ol.object_id = :object
                  and not exists (
                      select 1 from clearance c
                      where c.tenant_id = :tenant and c.principal_id = :principal and c.label_id = ol.label_id
                        and c.value_rank >= ol.value_rank and (c.expires_at is null or c.expires_at > :now))
                  and not exists (
                      select 1 from agreement a
                      join agreement_principal ap on ap.agreement_id = a.id and ap.principal_id = :principal
                      join agreement_scope s on s.agreement_id = a.id
                      where a.tenant_id = :tenant and a.active and a.label_id = ol.label_id and a.value_rank >= ol.value_rank
                        and a.valid_from <= :now and (a.valid_to is null or a.valid_to > :now)
                        and (s.object_id = ol.object_id or exists (
                            select 1 from context_closure cc where cc.ancestor_id = s.context_id and cc.descendant_id = :context)))
                order by sl.name
                """,
            ).param("object", objectId)
            .param("tenant", tenant)
            .param("principal", principal)
            .param("now", Timestamp.from(now))
            .param("context", contextId)
            .query {
                rs,
                _,
                ->
                Blocking(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getInt(4))
            }.list()

    private fun ResultSet.label() =
        SecurityLabel(
            getObject("id", UUID::class.java),
            getString("key"),
            getString("name"),
            getString("description"),
            values(getString("values")),
        )

    fun labels(tenant: UUID): List<SecurityLabel> =
        jdbc
            .sql("select * from security_label where tenant_id = :t order by name")
            .param("t", tenant)
            .query { rs, _ -> rs.label() }
            .list()

    fun label(id: UUID): SecurityLabel? =
        jdbc
            .sql("select * from security_label where id = :id")
            .param("id", id)
            .query { rs, _ -> rs.label() }
            .optional()
            .orElse(null)

    fun saveLabel(
        tenant: UUID,
        l: SecurityLabel,
        at: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into security_label (id, tenant_id, key, name, description, "values", created_at)
                values (:id, :t, :key, :name, :description, cast(:values as jsonb), :at)
                on conflict (id) do update set name = excluded.name, description = excluded.description, "values" = excluded."values"
                """,
            ).param("id", l.id)
            .param("t", tenant)
            .param("key", l.key)
            .param("name", l.name)
            .param("description", l.description)
            .param("values", json.writeValueAsString(l.values))
            .param("at", Timestamp.from(at))
            .update()
    }

    fun objectLabels(objectId: UUID): List<ObjectLabel> =
        jdbc
            .sql(
                """
                select ol.*, sl.key, sl.name, sl."values" from object_label ol join security_label sl on sl.id = ol.label_id
                where ol.object_id = :o order by sl.name
                """,
            ).param("o", objectId)
            .query { rs, _ ->
                val values = values(rs.getString("values"))
                val key = rs.getString("value_key")
                ObjectLabel(
                    rs.getObject("label_id", UUID::class.java),
                    rs.getString("key"),
                    rs.getString("name"),
                    key,
                    values.firstOrNull { it.key == key }?.label ?: key,
                    rs.getTimestamp("set_at").toInstant(),
                    rs.getString("set_by"),
                )
            }.list()

    fun setObjectLabel(
        tenant: UUID,
        objectId: UUID,
        labelId: UUID,
        valueKey: String,
        rank: Int,
        at: Instant,
        by: String,
    ) {
        jdbc
            .sql(
                """
                insert into object_label (object_id, label_id, tenant_id, value_key, value_rank, set_at, set_by)
                values (:o, :l, :t, :v, :r, :at, :by)
                on conflict (object_id, label_id) do update set value_key = excluded.value_key, value_rank = excluded.value_rank,
                    set_at = excluded.set_at, set_by = excluded.set_by
                """,
            ).param("o", objectId)
            .param("l", labelId)
            .param("t", tenant)
            .param("v", valueKey)
            .param("r", rank)
            .param("at", Timestamp.from(at))
            .param("by", by)
            .update()
    }

    fun removeObjectLabel(
        objectId: UUID,
        labelId: UUID,
    ) {
        jdbc
            .sql(
                "delete from object_label where object_id = :o and label_id = :l",
            ).param("o", objectId)
            .param("l", labelId)
            .update()
    }

    fun clearances(
        tenant: UUID,
        principal: String?,
    ): List<Clearance> =
        jdbc
            .sql(
                "select * from clearance where tenant_id = :t and (cast(:p as text) is null or principal_id = :p) order by principal_id",
            ).param("t", tenant)
            .param("p", principal)
            .query { rs, _ ->
                Clearance(
                    rs.getString("principal_id"),
                    rs.getObject("label_id", UUID::class.java),
                    rs.getString("value_key"),
                    rs.getTimestamp("expires_at")?.toInstant(),
                    rs.getTimestamp("granted_at").toInstant(),
                    rs.getString("granted_by"),
                )
            }.list()

    fun grant(
        tenant: UUID,
        c: Clearance,
        rank: Int,
    ) {
        jdbc
            .sql(
                """
                insert into clearance (tenant_id, principal_id, label_id, value_key, value_rank, expires_at, granted_at, granted_by)
                values (:t, :p, :l, :v, :r, :exp, :at, :by)
                on conflict (principal_id, label_id) do update set value_key = excluded.value_key, value_rank = excluded.value_rank,
                    expires_at = excluded.expires_at, granted_at = excluded.granted_at, granted_by = excluded.granted_by
                """,
            ).param("t", tenant)
            .param("p", c.principalId)
            .param("l", c.labelId)
            .param("v", c.valueKey)
            .param("r", rank)
            .param("exp", c.expiresAt?.let(Timestamp::from))
            .param("at", Timestamp.from(c.grantedAt))
            .param("by", c.grantedBy)
            .update()
    }

    fun revoke(
        principal: String,
        labelId: UUID,
    ): Int =
        jdbc
            .sql(
                "delete from clearance where principal_id = :p and label_id = :l",
            ).param("p", principal)
            .param("l", labelId)
            .update()

    private fun ResultSet.agreement(): Agreement {
        val id = getObject("id", UUID::class.java)
        return Agreement(
            id,
            getString("name"),
            getString("description"),
            getObject("label_id", UUID::class.java),
            getString("value_key"),
            getTimestamp("valid_from").toInstant(),
            getTimestamp("valid_to")?.toInstant(),
            getBoolean("active"),
            jdbc
                .sql(
                    "select principal_id from agreement_principal where agreement_id = :a order by 1",
                ).param("a", id)
                .query {
                    rs,
                    _,
                    ->
                    rs.getString(1)
                }.list(),
            jdbc
                .sql(
                    "select object_id from agreement_scope where agreement_id = :a and object_id is not null",
                ).param("a", id)
                .query {
                    rs,
                    _,
                    ->
                    rs.getObject(1, UUID::class.java)
                }.list(),
            jdbc
                .sql(
                    "select context_id from agreement_scope where agreement_id = :a and context_id is not null",
                ).param("a", id)
                .query {
                    rs,
                    _,
                    ->
                    rs.getObject(1, UUID::class.java)
                }.list(),
            getTimestamp("created_at").toInstant(),
            getString("created_by"),
        )
    }

    fun agreements(tenant: UUID): List<Agreement> =
        jdbc
            .sql("select * from agreement where tenant_id = :t order by valid_from desc")
            .param("t", tenant)
            .query { rs, _ -> rs.agreement() }
            .list()

    fun agreement(id: UUID): Agreement? =
        jdbc
            .sql("select * from agreement where id = :id")
            .param("id", id)
            .query { rs, _ -> rs.agreement() }
            .optional()
            .orElse(null)

    fun saveAgreement(
        tenant: UUID,
        a: Agreement,
        rank: Int,
    ) {
        jdbc
            .sql(
                """
                insert into agreement (id, tenant_id, name, description, label_id, value_key, value_rank, valid_from, valid_to, active, created_at, created_by)
                values (:id, :t, :name, :d, :l, :v, :r, :from, :to, :active, :at, :by)
                on conflict (id) do update set name = excluded.name, description = excluded.description, label_id = excluded.label_id,
                    value_key = excluded.value_key, value_rank = excluded.value_rank, valid_from = excluded.valid_from,
                    valid_to = excluded.valid_to, active = excluded.active
                """,
            ).param("id", a.id)
            .param("t", tenant)
            .param("name", a.name)
            .param("d", a.description)
            .param("l", a.labelId)
            .param("v", a.valueKey)
            .param("r", rank)
            .param("from", Timestamp.from(a.validFrom))
            .param("to", a.validTo?.let(Timestamp::from))
            .param("active", a.active)
            .param("at", Timestamp.from(a.createdAt))
            .param("by", a.createdBy)
            .update()
        jdbc.sql("delete from agreement_principal where agreement_id = :a").param("a", a.id).update()
        jdbc.sql("delete from agreement_scope where agreement_id = :a").param("a", a.id).update()
        a.principals.distinct().forEach {
            jdbc
                .sql(
                    "insert into agreement_principal (agreement_id, principal_id) values (:a, :p)",
                ).param("a", a.id)
                .param("p", it)
                .update()
        }
        a.objectIds.distinct().forEach {
            jdbc
                .sql(
                    "insert into agreement_scope (agreement_id, object_id) values (:a, :o)",
                ).param("a", a.id)
                .param("o", it)
                .update()
        }
        a.contextIds.distinct().forEach {
            jdbc
                .sql(
                    "insert into agreement_scope (agreement_id, context_id) values (:a, :c)",
                ).param("a", a.id)
                .param("c", it)
                .update()
        }
    }

    fun deleteAgreement(id: UUID): Int = jdbc.sql("delete from agreement where id = :id").param("id", id).update()

    /** The person's active agreements that cover [objectId] (or its context) for [labelId] up to [rank]. */
    fun coveringAgreements(
        tenant: UUID,
        principal: String,
        labelId: UUID,
        rank: Int,
        objectId: UUID,
        contextId: UUID,
        now: Instant,
    ): List<String> =
        jdbc
            .sql(
                """
                select distinct a.name from agreement a
                join agreement_principal ap on ap.agreement_id = a.id and ap.principal_id = :p
                join agreement_scope s on s.agreement_id = a.id
                where a.tenant_id = :t and a.active and a.label_id = :l and a.value_rank >= :r
                  and a.valid_from <= :now and (a.valid_to is null or a.valid_to > :now)
                  and (s.object_id = :o or exists (select 1 from context_closure cc where cc.ancestor_id = s.context_id and cc.descendant_id = :c))
                """,
            ).param("t", tenant)
            .param("p", principal)
            .param("l", labelId)
            .param("r", rank)
            .param("o", objectId)
            .param("c", contextId)
            .param("now", Timestamp.from(now))
            .query { rs, _ -> rs.getString(1) }
            .list()
}
