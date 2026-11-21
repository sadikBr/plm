package com.plm.search.api

import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** Columns, filters and sort of a table (roadmap 3.7). Column keys: `number`, `name`, …, or `attr:<name>`. */
data class TableViewDefinition(
    val columns: List<String>,
    val sortColumn: String? = null,
    val sortDescending: Boolean = false,
    /** Text the number or name must contain. */
    val text: String? = null,
    val states: List<String> = emptyList(),
    val typeIds: List<UUID> = emptyList(),
    /** Attribute name → text its value must contain. */
    val attributes: Map<String, String> = emptyMap(),
)

data class TableView(
    val id: UUID,
    val table: String,
    val name: String,
    val owner: String,
    val shared: Boolean,
    val definition: TableViewDefinition,
    val updatedAt: Instant,
)

data class SaveTableView(
    val table: String,
    val name: String,
    val definition: TableViewDefinition,
    val shared: Boolean = false,
)

/** Saved table views: your own and shared ones. Error codes: `TABLE_VIEW_NOT_FOUND`, `INVALID_TABLE_VIEW`, `DUPLICATE_TABLE_VIEW`. */
interface TableViewService {
    fun list(
        actor: Actor,
        table: String,
    ): List<TableView>

    fun save(
        actor: Actor,
        request: SaveTableView,
    ): TableView

    fun update(
        actor: Actor,
        id: UUID,
        request: SaveTableView,
    ): TableView

    fun delete(
        actor: Actor,
        id: UUID,
    )
}

/** A table as shown, to export: column titles and the cells' text or numbers ("" for an empty cell). */
data class SheetExport(
    val sheetName: String = "Objects",
    val columns: List<String>,
    val rows: List<List<Any?>>,
)
