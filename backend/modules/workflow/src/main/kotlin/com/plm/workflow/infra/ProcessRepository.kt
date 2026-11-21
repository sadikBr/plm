package com.plm.workflow.infra

import com.plm.platform.id.TenantId
import com.plm.workflow.api.NodeKind
import com.plm.workflow.api.ProcessDefinition
import com.plm.workflow.api.ProcessEvent
import com.plm.workflow.api.ProcessStatus
import com.plm.workflow.api.ProcessTemplate
import com.plm.workflow.api.ProcessTemplateVersion
import com.plm.workflow.api.WorkItemStatus
import com.plm.workflow.domain.Definitions
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A process as stored: its subject's number and name are filled in by the service. */
internal data class InstanceRow(
    val id: UUID,
    val tenantId: UUID,
    val templateId: UUID,
    val templateVersion: Int,
    val name: String,
    val subjectId: UUID?,
    val status: ProcessStatus,
    val outcome: String?,
    val startedBy: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val comment: String?,
    val current: Map<String, String>,
    val contextId: UUID? = null,
    val about: String? = null,
    val link: String? = null,
)

internal data class WorkItemRow(
    val id: UUID,
    val tenantId: UUID,
    val instanceId: UUID,
    val nodeId: String,
    val visit: Int,
    val kind: NodeKind,
    val title: String,
    val instructions: String?,
    val assignee: String,
    val choices: List<String>,
    val signature: Boolean,
    val status: WorkItemStatus,
    val outcome: String?,
    val comment: String?,
    val subjectId: UUID?,
    val createdAt: Instant,
    val dueAt: Instant?,
    val completedAt: Instant?,
    val completedBy: String?,
    val claimedBy: String? = null,
    val onBehalfOf: String? = null,
)

@Repository
internal class ProcessRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun ResultSet.instant(c: String): Instant? = getTimestamp(c)?.toInstant()

    private fun strings(text: String): Map<String, String> =
        json.readValue(text, Map::class.java).entries.associate { it.key.toString() to it.value.toString() }

    private fun ResultSet.uuid(c: String): UUID? = getObject(c, UUID::class.java)

    // ---------- Templates ----------

    private val templateMapper =
        RowMapper { rs, _ ->
            ProcessTemplate(
                id = rs.uuid("id")!!,
                name = rs.getString("name"),
                description = rs.getString("description"),
                version = rs.getInt("version"),
                definition = Definitions.read(rs.getString("definition")),
                active = rs.getBoolean("active"),
                updatedAt = rs.instant("updated_at")!!,
                updatedBy = rs.getString("updated_by"),
            )
        }

    private val templateSelect =
        """
        select t.*, v.definition from process_template t
        join process_template_version v on v.template_id = t.id and v.version = t.version
        """.trimIndent()

    fun templates(tenant: TenantId): List<ProcessTemplate> =
        jdbc
            .sql("$templateSelect where t.tenant_id = :tenant order by lower(t.name)")
            .param("tenant", tenant.value)
            .query(templateMapper)
            .list()

    fun template(
        tenant: TenantId,
        id: UUID,
    ): ProcessTemplate? =
        jdbc
            .sql("$templateSelect where t.tenant_id = :tenant and t.id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query(templateMapper)
            .optional()
            .orElse(null)

    fun definition(
        templateId: UUID,
        version: Int,
    ): ProcessDefinition =
        Definitions.read(
            jdbc
                .sql("select definition from process_template_version where template_id = :id and version = :v")
                .param("id", templateId)
                .param("v", version)
                .query(String::class.java)
                .single(),
        )

    fun versions(templateId: UUID): List<ProcessTemplateVersion> =
        jdbc
            .sql("select * from process_template_version where template_id = :id order by version desc")
            .param("id", templateId)
            .query {
                rs,
                _,
                ->
                ProcessTemplateVersion(rs.getInt("version"), rs.instant("created_at")!!, rs.getString("created_by"))
            }.list()

    fun saveTemplate(
        tenant: TenantId,
        id: UUID,
        name: String,
        description: String?,
        active: Boolean,
        definition: ProcessDefinition,
        by: String,
        now: Instant,
    ): Int {
        val version =
            jdbc
                .sql("select coalesce(max(version), 0) + 1 from process_template_version where template_id = :id")
                .param("id", id)
                .query(Int::class.java)
                .single()
        jdbc
            .sql(
                """
                insert into process_template (id, tenant_id, name, description, version, active, updated_at, updated_by)
                values (:id, :tenant, :name, :description, :version, :active, :now, :by)
                on conflict (id) do update set name = excluded.name, description = excluded.description,
                    version = excluded.version, active = excluded.active, updated_at = excluded.updated_at,
                    updated_by = excluded.updated_by
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", tenant.value)
            .param("name", name)
            .param("description", description)
            .param("version", version)
            .param("active", active)
            .param("now", Timestamp.from(now))
            .param("by", by)
            .update()
        jdbc
            .sql(
                """
                insert into process_template_version (template_id, version, definition, created_at, created_by)
                values (:id, :version, cast(:definition as jsonb), :now, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("version", version)
            .param("definition", Definitions.write(definition))
            .param("now", Timestamp.from(now))
            .param("by", by)
            .update()
        return version
    }

    // ---------- Instances ----------

    private val instanceMapper =
        RowMapper { rs, _ ->
            InstanceRow(
                id = rs.uuid("id")!!,
                tenantId = rs.uuid("tenant_id")!!,
                templateId = rs.uuid("template_id")!!,
                templateVersion = rs.getInt("template_version"),
                name = rs.getString("name"),
                subjectId = rs.uuid("subject_id"),
                status = ProcessStatus.valueOf(rs.getString("status")),
                outcome = rs.getString("outcome"),
                startedBy = rs.getString("started_by"),
                startedAt = rs.instant("started_at")!!,
                endedAt = rs.instant("ended_at"),
                comment = rs.getString("comment"),
                current = strings(rs.getString("current")),
                contextId = rs.uuid("context_id"),
                about = rs.getString("about"),
                link = rs.getString("link"),
            )
        }

    fun insertInstance(row: InstanceRow) {
        jdbc
            .sql(
                """
                insert into process_instance (id, tenant_id, template_id, template_version, name, subject_id, status,
                    started_by, started_at, comment, context_id, about, link)
                values (:id, :tenant, :template, :version, :name, :subject, :status, :by, :at, :comment, :context,
                    :about, :link)
                """.trimIndent(),
            ).param("id", row.id)
            .param("tenant", row.tenantId)
            .param("template", row.templateId)
            .param("version", row.templateVersion)
            .param("name", row.name)
            .param("subject", row.subjectId)
            .param("status", row.status.name)
            .param("by", row.startedBy)
            .param("at", Timestamp.from(row.startedAt))
            .param("comment", row.comment)
            .param("context", row.contextId)
            .param("about", row.about)
            .param("link", row.link)
            .update()
    }

    fun instance(id: UUID): InstanceRow? =
        jdbc
            .sql("select * from process_instance where id = :id")
            .param("id", id)
            .query(instanceMapper)
            .optional()
            .orElse(null)

    fun instances(
        tenant: TenantId,
        subjectId: UUID? = null,
        status: ProcessStatus? = null,
        limit: Int = 100,
    ): List<InstanceRow> =
        jdbc
            .sql(
                """
                select * from process_instance where tenant_id = :tenant
                  and (cast(:subject as uuid) is null or subject_id = :subject)
                  and (cast(:status as text) is null or status = :status)
                order by started_at desc limit :limit
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("subject", subjectId)
            .param("status", status?.name)
            .param("limit", limit)
            .query(instanceMapper)
            .list()

    fun setCurrent(
        id: UUID,
        nodeId: String,
        label: String?,
    ) {
        val sql =
            if (label == null) {
                "update process_instance set current = current - :node where id = :id"
            } else {
                "update process_instance set current = current || jsonb_build_object(cast(:node as text), cast(:label as text)) where id = :id"
            }
        jdbc
            .sql(sql)
            .param("id", id)
            .param("node", nodeId)
            .apply { if (label != null) param("label", label) }
            .update()
    }

    fun finish(
        id: UUID,
        status: ProcessStatus,
        outcome: String?,
        at: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                update process_instance set status = :status, outcome = :outcome, ended_at = :at, current = '{}'
                where id = :id and status = 'RUNNING'
                """.trimIndent(),
            ).param("id", id)
            .param("status", status.name)
            .param("outcome", outcome)
            .param("at", Timestamp.from(at))
            .update() == 1

    fun event(
        instanceId: UUID,
        at: Instant,
        kind: String,
        nodeId: String?,
        actor: String?,
        message: String,
    ) {
        jdbc
            .sql(
                """
                insert into process_event (instance_id, at, kind, node_id, actor, message)
                values (:id, :at, :kind, :node, :actor, :message)
                """.trimIndent(),
            ).param("id", instanceId)
            .param("at", Timestamp.from(at))
            .param("kind", kind)
            .param("node", nodeId)
            .param("actor", actor)
            .param("message", message.take(2000))
            .update()
    }

    fun events(instanceId: UUID): List<ProcessEvent> =
        jdbc
            .sql("select * from process_event where instance_id = :id order by id")
            .param("id", instanceId)
            .query { rs, _ ->
                ProcessEvent(
                    rs.instant("at")!!,
                    rs.getString("kind"),
                    rs.getString("node_id"),
                    rs.getString("actor"),
                    rs.getString("message"),
                )
            }.list()

    // ---------- Work items ----------

    private val itemMapper =
        RowMapper { rs, _ ->
            WorkItemRow(
                id = rs.uuid("id")!!,
                tenantId = rs.uuid("tenant_id")!!,
                instanceId = rs.uuid("instance_id")!!,
                nodeId = rs.getString("node_id"),
                visit = rs.getInt("visit"),
                kind = NodeKind.valueOf(rs.getString("kind")),
                title = rs.getString("title"),
                instructions = rs.getString("instructions"),
                assignee = rs.getString("assignee"),
                choices = json.readValue(rs.getString("choices"), List::class.java).map { it.toString() },
                signature = rs.getBoolean("signature"),
                status = WorkItemStatus.valueOf(rs.getString("status")),
                outcome = rs.getString("outcome"),
                comment = rs.getString("comment"),
                subjectId = rs.uuid("subject_id"),
                createdAt = rs.instant("created_at")!!,
                dueAt = rs.instant("due_at"),
                completedAt = rs.instant("completed_at"),
                completedBy = rs.getString("completed_by"),
                claimedBy = rs.getString("claimed_by"),
                onBehalfOf = rs.getString("on_behalf_of"),
            )
        }

    fun reassign(
        id: UUID,
        to: String,
        onBehalfOf: String,
    ) {
        jdbc
            .sql(
                "update work_item set assignee = :to, on_behalf_of = :for, claimed_by = null, claimed_at = null where id = :id",
            ).param("id", id)
            .param("to", to)
            .param("for", onBehalfOf)
            .update()
    }

    fun standIn(
        tenant: TenantId,
        principal: String,
    ): com.plm.workflow.api.StandIn? =
        jdbc
            .sql("select * from task_stand_in where tenant_id = :tenant and principal = :p")
            .param("tenant", tenant.value)
            .param("p", principal)
            .query { rs, _ ->
                com.plm.workflow.api.StandIn(
                    rs.getString("stand_in"),
                    rs.getObject("from_date", java.time.LocalDate::class.java),
                    rs.getObject("until_date", java.time.LocalDate::class.java),
                )
            }.optional()
            .orElse(null)

    fun setStandIn(
        tenant: TenantId,
        principal: String,
        standIn: com.plm.workflow.api.StandIn?,
    ) {
        jdbc
            .sql("delete from task_stand_in where tenant_id = :tenant and principal = :p")
            .param("tenant", tenant.value)
            .param("p", principal)
            .update()
        if (standIn == null) return
        jdbc
            .sql(
                """
                insert into task_stand_in (tenant_id, principal, stand_in, from_date, until_date)
                values (:tenant, :p, :s, :from, :until)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("p", principal)
            .param("s", standIn.standIn)
            .param("from", standIn.from)
            .param("until", standIn.until)
            .update()
    }

    /** Sets (or with null, clears) the claim on every open copy of a step pass. */
    fun claim(
        instanceId: UUID,
        nodeId: String,
        visit: Int,
        by: String?,
        at: Instant,
    ) {
        jdbc
            .sql(
                """
                update work_item set claimed_by = :by, claimed_at = :at
                where instance_id = :id and node_id = :node and visit = :visit and status = 'OPEN'
                """.trimIndent(),
            ).param("id", instanceId)
            .param("node", nodeId)
            .param("visit", visit)
            .param("by", by)
            .param("at", by?.let { Timestamp.from(at) })
            .update()
    }

    fun overdueCount(
        tenant: TenantId,
        assignee: String,
        now: Instant,
    ): Int =
        jdbc
            .sql(
                """
                select count(*) from work_item where tenant_id = :tenant and assignee = :assignee and status = 'OPEN'
                  and due_at < :now
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("assignee", assignee)
            .param("now", Timestamp.from(now))
            .query(Int::class.java)
            .single()

    fun insertItem(row: WorkItemRow) {
        jdbc
            .sql(
                """
                insert into work_item (id, tenant_id, instance_id, node_id, visit, kind, title, instructions, assignee,
                    choices, signature, status, subject_id, created_at, due_at, on_behalf_of)
                values (:id, :tenant, :instance, :node, :visit, :kind, :title, :instructions, :assignee,
                    cast(:choices as jsonb), :signature, 'OPEN', :subject, :created, :due, :for)
                """.trimIndent(),
            ).param("id", row.id)
            .param("tenant", row.tenantId)
            .param("instance", row.instanceId)
            .param("node", row.nodeId)
            .param("visit", row.visit)
            .param("kind", row.kind.name)
            .param("title", row.title)
            .param("instructions", row.instructions)
            .param("assignee", row.assignee)
            .param("choices", json.writeValueAsString(row.choices))
            .param("signature", row.signature)
            .param("subject", row.subjectId)
            .param("created", Timestamp.from(row.createdAt))
            .param("due", row.dueAt?.let(Timestamp::from))
            .param("for", row.onBehalfOf)
            .update()
    }

    fun item(id: UUID): WorkItemRow? =
        jdbc
            .sql("select * from work_item where id = :id")
            .param("id", id)
            .query(itemMapper)
            .optional()
            .orElse(null)

    fun items(
        tenant: TenantId,
        assignee: String?,
        status: WorkItemStatus?,
        limit: Int,
    ): List<WorkItemRow> =
        jdbc
            .sql(
                """
                select * from work_item where tenant_id = :tenant
                  and (cast(:assignee as text) is null or assignee = :assignee)
                  and (cast(:status as text) is null or status = :status)
                order by status = 'OPEN' desc, coalesce(due_at, created_at + interval '100 years'), created_at desc
                limit :limit
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("assignee", assignee)
            .param("status", status?.name)
            .param("limit", limit)
            .query(itemMapper)
            .list()

    fun itemsOf(
        instanceId: UUID,
        nodeId: String? = null,
        visit: Int? = null,
    ): List<WorkItemRow> =
        jdbc
            .sql(
                """
                select * from work_item where instance_id = :id
                  and (cast(:node as text) is null or node_id = :node)
                  and (cast(:visit as int) is null or visit = :visit)
                order by created_at, assignee
                """.trimIndent(),
            ).param("id", instanceId)
            .param("node", nodeId)
            .param("visit", visit)
            .query(itemMapper)
            .list()

    fun completeItem(
        id: UUID,
        outcome: String,
        comment: String?,
        by: String,
        at: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                update work_item set status = 'DONE', outcome = :outcome, comment = :comment, completed_by = :by,
                    completed_at = :at
                where id = :id and status = 'OPEN'
                """.trimIndent(),
            ).param("id", id)
            .param("outcome", outcome)
            .param("comment", comment)
            .param("by", by)
            .param("at", Timestamp.from(at))
            .update() == 1

    fun cancelOpen(
        instanceId: UUID,
        nodeId: String? = null,
        visit: Int? = null,
        at: Instant,
    ): Int =
        jdbc
            .sql(
                """
                update work_item set status = 'CANCELLED', completed_at = :at
                where instance_id = :id and status = 'OPEN'
                  and (cast(:node as text) is null or node_id = :node)
                  and (cast(:visit as int) is null or visit = :visit)
                """.trimIndent(),
            ).param("id", instanceId)
            .param("node", nodeId)
            .param("visit", visit)
            .param("at", Timestamp.from(at))
            .update()

    fun openCount(
        tenant: TenantId,
        assignee: String,
    ): Int =
        jdbc
            .sql(
                "select count(*) from work_item where tenant_id = :tenant and assignee = :assignee and status = 'OPEN'",
            ).param("tenant", tenant.value)
            .param("assignee", assignee)
            .query(Int::class.java)
            .single()
}
