package com.plm.lifecycle.infra

import com.plm.lifecycle.api.LifecycleState
import com.plm.lifecycle.api.LifecycleTemplate
import com.plm.lifecycle.api.LifecycleTemplateId
import com.plm.lifecycle.api.LifecycleTransition
import com.plm.platform.error.ConflictException
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Repository
internal class LifecycleRepository(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()
    private val labels = object : TypeReference<Map<String, String>>() {}

    fun templates(
        tenant: TenantId,
        ids: Collection<LifecycleTemplateId>? = null,
    ): List<LifecycleTemplate> {
        if (ids != null && ids.isEmpty()) return emptyList()

        data class Head(
            val id: LifecycleTemplateId,
            val name: String,
            val labels: Map<String, String>,
            val initial: String,
            val system: Boolean,
        )
        val heads =
            jdbc
                .sql(
                    "select * from lifecycle_template where tenant_id = :tenant" +
                        (if (ids != null) " and id in (:ids)" else "") +
                        " order by name",
                ).param("tenant", tenant.value)
                .let { q -> if (ids != null) q.param("ids", ids.map { it.value }) else q }
                .query { rs, _ ->
                    Head(
                        LifecycleTemplateId(rs.getObject("id", UUID::class.java)),
                        rs.getString("name"),
                        json.readValue(rs.getString("labels"), labels),
                        rs.getString("initial_state"),
                        rs.getBoolean("system"),
                    )
                }.list()
        if (heads.isEmpty()) return emptyList()
        val templateIds = heads.map { it.id.value }
        val states =
            jdbc
                .sql("select * from lifecycle_state where template_id in (:ids) order by sort_order, key")
                .param("ids", templateIds)
                .query { rs, _ ->
                    rs.getObject("template_id", UUID::class.java) to
                        LifecycleState(
                            rs.getString("key"),
                            json.readValue(rs.getString("labels"), labels),
                            rs.getBoolean("editable"),
                            rs.getBoolean("revisable"),
                        )
                }.list()
                .groupBy({ it.first }, { it.second })
        val transitions =
            jdbc
                .sql("select * from lifecycle_transition where template_id in (:ids) order by from_state, to_state")
                .param("ids", templateIds)
                .query { rs, _ ->
                    rs.getObject("template_id", UUID::class.java) to
                        LifecycleTransition(rs.getString("from_state"), rs.getString("to_state"))
                }.list()
                .groupBy({ it.first }, { it.second })
        return heads.map {
            LifecycleTemplate(
                it.id,
                it.name,
                it.labels,
                it.initial,
                states[it.id.value].orEmpty(),
                transitions[it.id.value].orEmpty(),
                it.system,
            )
        }
    }

    fun insert(
        tenant: TenantId,
        t: LifecycleTemplate,
    ) {
        try {
            jdbc
                .sql(
                    """
                    insert into lifecycle_template (id, tenant_id, name, labels, initial_state)
                    values (:id, :tenant, :name, cast(:labels as jsonb), :initial)
                    """,
                ).param("id", t.id.value)
                .param("tenant", tenant.value)
                .param("name", t.name)
                .param("labels", json.writeValueAsString(t.labels))
                .param("initial", t.initialState)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("DUPLICATE_LIFECYCLE_NAME", "A lifecycle named '${t.name}' already exists").apply {
                initCause(e)
            }
        }
        t.states.forEachIndexed { i, s ->
            jdbc
                .sql(
                    """
                    insert into lifecycle_state (template_id, key, labels, editable, revisable, sort_order)
                    values (:id, :key, cast(:labels as jsonb), :editable, :revisable, :order)
                    """,
                ).param("id", t.id.value)
                .param("key", s.key)
                .param("labels", json.writeValueAsString(s.labels))
                .param("editable", s.editable)
                .param("revisable", s.revisable)
                .param("order", i + 1)
                .update()
        }
        t.transitions.forEach { tr ->
            jdbc
                .sql("insert into lifecycle_transition (template_id, from_state, to_state) values (:id, :from, :to)")
                .param("id", t.id.value)
                .param("from", tr.from)
                .param("to", tr.to)
                .update()
        }
    }

    fun assignments(types: List<TypeId>): Map<TypeId, LifecycleTemplateId> {
        if (types.isEmpty()) return emptyMap()
        return jdbc
            .sql("select type_id, template_id from type_lifecycle where type_id in (:types)")
            .param("types", types.map { it.value })
            .query { rs, _ ->
                TypeId(rs.getObject("type_id", UUID::class.java)) to
                    LifecycleTemplateId(rs.getObject("template_id", UUID::class.java))
            }.list()
            .toMap()
    }

    fun assign(
        tenant: TenantId,
        type: TypeId,
        template: LifecycleTemplateId,
    ) {
        jdbc
            .sql(
                """
                insert into type_lifecycle (type_id, tenant_id, template_id) values (:type, :tenant, :template)
                on conflict (type_id) do update set template_id = excluded.template_id
                """,
            ).param("type", type.value)
            .param("tenant", tenant.value)
            .param("template", template.value)
            .update()
    }
}
