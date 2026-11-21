package com.plm.search.domain

import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.UuidV7
import com.plm.search.api.SaveTableView
import com.plm.search.api.TableView
import com.plm.search.api.TableViewDefinition
import com.plm.search.api.TableViewService
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

@Service
@Transactional
internal class DefaultTableViewService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : TableViewService {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Transactional(readOnly = true)
    override fun list(
        actor: Actor,
        table: String,
    ): List<TableView> =
        jdbc
            .sql(
                """
                select * from table_view where tenant_id = :tenant and table_key = :table and (owner = :me or shared)
                order by owner = :me desc, lower(name)
                """.trimIndent(),
            ).param("tenant", actor.tenant.value)
            .param("table", table)
            .param("me", actor.principal.value)
            .query { rs, _ ->
                TableView(
                    id = rs.getObject("id", UUID::class.java),
                    table = rs.getString("table_key"),
                    name = rs.getString("name"),
                    owner = rs.getString("owner"),
                    shared = rs.getBoolean("shared"),
                    definition = json.readValue(rs.getString("definition"), TableViewDefinition::class.java),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }.list()

    override fun save(
        actor: Actor,
        request: SaveTableView,
    ): TableView = write(actor, null, request)

    override fun update(
        actor: Actor,
        id: UUID,
        request: SaveTableView,
    ): TableView = write(actor, id, request)

    override fun delete(
        actor: Actor,
        id: UUID,
    ) {
        mine(actor, id)
        jdbc.sql("delete from table_view where id = :id").param("id", id).update()
    }

    private fun mine(
        actor: Actor,
        id: UUID,
    ) {
        val owner =
            jdbc
                .sql("select owner from table_view where tenant_id = :tenant and id = :id")
                .param("tenant", actor.tenant.value)
                .param("id", id)
                .query(String::class.java)
                .optional()
                .orElse(null) ?: throw NotFoundException("TABLE_VIEW_NOT_FOUND", "There's no such view")
        if (owner != actor.principal.value) throw ForbiddenException("ACCESS_DENIED", "Only its owner can change it")
    }

    private fun write(
        actor: Actor,
        id: UUID?,
        request: SaveTableView,
    ): TableView {
        val name = request.name.trim()
        if (name.isEmpty() || name.length > 100) throw ValidationException("INVALID_TABLE_VIEW", "Give the view a name")
        if (request.definition.columns.isEmpty() || request.definition.columns.size > 60) {
            throw ValidationException("INVALID_TABLE_VIEW", "A view shows 1 to 60 columns")
        }
        if (id != null) mine(actor, id)
        val newId = id ?: UuidV7.generate(clock)
        val now = Timestamp.from(clock.instant())
        try {
            jdbc
                .sql(
                    """
                    insert into table_view (id, tenant_id, owner, table_key, name, definition, shared, created_at, updated_at)
                    values (:id, :tenant, :owner, :table, :name, cast(:definition as jsonb), :shared, :now, :now)
                    on conflict (id) do update set name = excluded.name, definition = excluded.definition,
                        shared = excluded.shared, updated_at = excluded.updated_at
                    """.trimIndent(),
                ).param("id", newId)
                .param("tenant", actor.tenant.value)
                .param("owner", actor.principal.value)
                .param("table", request.table)
                .param("name", name)
                .param("definition", json.writeValueAsString(request.definition))
                .param("shared", request.shared)
                .param("now", now)
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("DUPLICATE_TABLE_VIEW", "You already have a view named $name")
        }
        return list(actor, request.table).first { it.id == newId }
    }
}
