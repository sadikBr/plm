package com.plm.bom.infra

import com.plm.access.api.SqlPredicate
import com.plm.bom.api.DocumentLink
import com.plm.bom.api.DocumentLinkKind
import com.plm.bom.api.DocumentLinkRule
import com.plm.bom.api.LinkingPart
import com.plm.bom.api.UsedPart
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class LinkRecord(
    val id: UUID,
    val partIterationId: UUID,
    val kind: DocumentLinkKind,
    val documentMasterId: UUID,
    val documentIterationId: UUID?,
)

@Repository
internal class DocumentLinkRepository(
    private val jdbc: JdbcClient,
) {
    private fun ResultSet.used(
        prefix: String,
        readable: Boolean,
    ) = if (!readable) {
        UsedPart(getObject("${prefix}master_id", UUID::class.java), false, null, null, null, null, null, null, false)
    } else {
        UsedPart(
            getObject("${prefix}master_id", UUID::class.java),
            true,
            getString("${prefix}number"),
            getString("${prefix}name"),
            getObject("${prefix}type_id", UUID::class.java),
            getObject("${prefix}iteration_id", UUID::class.java),
            getString("${prefix}revision") + "." + getInt("${prefix}iteration"),
            getString("${prefix}state"),
            false,
        )
    }

    /** The links on a part iteration, documents as [readable] lets the reader see them. */
    fun links(
        partIteration: UUID,
        readable: SqlPredicate,
    ): List<DocumentLink> =
        jdbc
            .sql(
                """
                select l.id, l.kind, l.created_at, l.created_by, m.id as d_master_id, m.number as d_number, m.name as d_name,
                       m.type_id as d_type_id, i.id as d_iteration_id, i.revision as d_revision, i.iteration as d_iteration,
                       i.state as d_state, (l.document_iteration_id is not null and l.document_iteration_id <> m.latest_iteration_id) as newer,
                       (${readable.sql}) as readable
                from part_document_link l
                join object_master m on m.id = l.document_master_id
                join object_iteration li on li.id = m.latest_iteration_id
                join object_iteration i on i.id = coalesce(l.document_iteration_id, m.latest_iteration_id)
                where l.part_iteration_id = :it
                order by l.kind, m.number
                """.trimIndent(),
            ).params(readable.params)
            .param("it", partIteration)
            .query { rs, _ ->
                DocumentLink(
                    rs.getObject("id", UUID::class.java),
                    DocumentLinkKind.valueOf(rs.getString("kind")),
                    rs.used("d_", rs.getBoolean("readable")),
                    rs.getBoolean("newer"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("created_by"),
                )
            }.list()

    /** Readable parts whose latest iteration links to [document]. */
    fun linkingParts(
        document: UUID,
        readable: SqlPredicate,
    ): List<LinkingPart> =
        jdbc
            .sql(
                """
                select l.kind, m.id as p_master_id, m.number as p_number, m.name as p_name, m.type_id as p_type_id,
                       li.id as p_iteration_id, li.revision as p_revision, li.iteration as p_iteration, li.state as p_state,
                       di.revision as d_revision, di.iteration as d_iteration
                from part_document_link l
                join object_master m on m.latest_iteration_id = l.part_iteration_id
                join object_iteration li on li.id = m.latest_iteration_id
                left join object_iteration di on di.id = l.document_iteration_id
                where l.document_master_id = :doc and ${readable.sql}
                order by m.number, l.kind
                """.trimIndent(),
            ).params(readable.params)
            .param("doc", document)
            .query { rs, _ ->
                LinkingPart(
                    DocumentLinkKind.valueOf(rs.getString("kind")),
                    rs.used("p_", true),
                    rs.getString("d_revision")?.let { it + "." + rs.getInt("d_iteration") },
                )
            }.list()

    fun record(id: UUID): LinkRecord? =
        jdbc
            .sql("select * from part_document_link where id = :id")
            .param("id", id)
            .query { rs, _ ->
                LinkRecord(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("part_iteration_id", UUID::class.java),
                    DocumentLinkKind.valueOf(rs.getString("kind")),
                    rs.getObject("document_master_id", UUID::class.java),
                    rs.getObject("document_iteration_id", UUID::class.java),
                )
            }.optional()
            .orElse(null)

    fun exists(
        partIteration: UUID,
        kind: DocumentLinkKind,
        document: UUID,
    ): Boolean =
        jdbc
            .sql(
                "select exists (select 1 from part_document_link where part_iteration_id = :it and kind = :kind and document_master_id = :doc)",
            ).param("it", partIteration)
            .param("kind", kind.name)
            .param("doc", document)
            .query(Boolean::class.java)
            .single()

    fun insert(
        tenant: TenantId,
        id: UUID,
        partIteration: UUID,
        kind: DocumentLinkKind,
        document: UUID,
        documentIteration: UUID?,
        at: Instant,
        by: String,
    ) {
        jdbc
            .sql(
                """
                insert into part_document_link (id, tenant_id, part_iteration_id, kind, document_master_id, document_iteration_id, created_at, created_by)
                values (:id, :tenant, :it, :kind, :doc, :docIt, :at, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", tenant.value)
            .param("it", partIteration)
            .param("kind", kind.name)
            .param("doc", document)
            .param("docIt", documentIteration)
            .param("at", Timestamp.from(at))
            .param("by", by)
            .update()
    }

    fun pointTo(
        id: UUID,
        documentIteration: UUID,
    ) {
        jdbc
            .sql(
                "update part_document_link set document_iteration_id = :it where id = :id",
            ).param("id", id)
            .param("it", documentIteration)
            .update()
    }

    fun delete(id: UUID) {
        jdbc.sql("delete from part_document_link where id = :id").param("id", id).update()
    }

    /** Copies the links of the iteration [iterationId] is based on (check-out, revise). */
    fun carryOver(
        iterationId: UUID,
        newId: () -> UUID,
    ) {
        val rows =
            jdbc
                .sql(
                    """
                    select l.* from part_document_link l join object_iteration i on i.based_on_id = l.part_iteration_id
                    where i.id = :it and not exists (select 1 from part_document_link x where x.part_iteration_id = :it)
                    """.trimIndent(),
                ).param("it", iterationId)
                .query { rs, _ ->
                    listOf(
                        rs.getObject("tenant_id", UUID::class.java),
                        rs.getString("kind"),
                        rs.getObject("document_master_id", UUID::class.java),
                        rs.getObject("document_iteration_id", UUID::class.java),
                        rs.getTimestamp("created_at"),
                        rs.getString("created_by"),
                    )
                }.list()
        for (r in rows) {
            jdbc
                .sql(
                    """
                    insert into part_document_link (id, tenant_id, part_iteration_id, kind, document_master_id, document_iteration_id, created_at, created_by)
                    values (:id, :tenant, :it, :kind, :doc, :docIt, :at, :by)
                    """.trimIndent(),
                ).param("id", newId())
                .param("tenant", r[0])
                .param("it", iterationId)
                .param("kind", r[1])
                .param("doc", r[2])
                .param("docIt", r[3])
                .param("at", r[4])
                .param("by", r[5])
                .update()
        }
    }

    // ---------- Rules ----------

    fun rules(tenant: TenantId): List<DocumentLinkRule> =
        jdbc
            .sql(
                "select * from part_document_rule where tenant_id = :tenant order by part_type_id, document_type_id, kind",
            ).param("tenant", tenant.value)
            .query { rs, _ ->
                DocumentLinkRule(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("part_type_id", UUID::class.java),
                    rs.getObject("document_type_id", UUID::class.java),
                    DocumentLinkKind.valueOf(rs.getString("kind")),
                )
            }.list()

    fun addRule(
        tenant: TenantId,
        rule: DocumentLinkRule,
    ) {
        jdbc
            .sql(
                """
                insert into part_document_rule (id, tenant_id, part_type_id, document_type_id, kind) values (:id, :tenant, :p, :d, :kind)
                on conflict do nothing
                """.trimIndent(),
            ).param("id", rule.id)
            .param("tenant", tenant.value)
            .param("p", rule.partTypeId)
            .param("d", rule.documentTypeId)
            .param("kind", rule.kind.name)
            .update()
    }

    fun removeRule(
        tenant: TenantId,
        id: UUID,
    ): Boolean =
        jdbc
            .sql("delete from part_document_rule where tenant_id = :tenant and id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .update() > 0
}
