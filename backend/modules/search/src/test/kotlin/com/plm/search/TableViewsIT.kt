package com.plm.search

import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.search.api.SaveTableView
import com.plm.search.api.SheetExport
import com.plm.search.api.TableViewDefinition
import com.plm.search.api.TableViewService
import com.plm.search.domain.Xlsx
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Table views and the Excel export (roadmap 3.7). */
@PlmIntegrationTest
internal class TableViewsIT(
    @Autowired private val views: TableViewService,
) {
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))
    private val table = "objects-${UUID.randomUUID()}"

    private fun code(call: () -> Unit) = (runCatching(call).exceptionOrNull() as PlmException).code

    @Test
    fun `views are saved per person, shared ones are seen by all and changed only by their owner`() {
        val def =
            TableViewDefinition(
                listOf("number", "name", "attr:material"),
                sortColumn = "attr:material",
                states = listOf("RELEASED"),
            )
        val mine = views.save(alice, SaveTableView(table, "Released by material", def))
        val shared =
            views.save(
                alice,
                SaveTableView(table, "Team view", def.copy(columns = listOf("number", "state")), shared = true),
            )
        assertThat(views.list(alice, table).map { it.name }).containsExactly("Released by material", "Team view")
        assertThat(views.list(bob, table).map { it.id }).containsExactly(shared.id)
        assertThat(
            views
                .list(alice, table)
                .first()
                .definition.columns,
        ).containsExactly("number", "name", "attr:material")

        assertThat(code { views.save(alice, SaveTableView(table, "Team view", def)) }).isEqualTo("DUPLICATE_TABLE_VIEW")
        assertThat(
            code {
                views.save(alice, SaveTableView(table, "Empty", def.copy(columns = emptyList())))
            },
        ).isEqualTo("INVALID_TABLE_VIEW")
        assertThat(code { views.delete(bob, shared.id) }).isEqualTo("ACCESS_DENIED")
        views.update(alice, mine.id, SaveTableView(table, "Renamed", def))
        views.delete(alice, shared.id)
        assertThat(views.list(alice, table).map { it.name }).containsExactly("Renamed")
    }

    @Test
    fun `the export is a workbook with the columns and cells as shown`() {
        val bytes =
            Xlsx.write(
                SheetExport(
                    "Rover <R2>",
                    listOf("Number", "Mass"),
                    listOf(listOf("0001 & co", 12.5), listOf("0002", null)),
                ),
            )
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readAllBytes().decodeToString()
            }
        }
        assertThat(
            entries.keys,
        ).contains("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/styles.xml")
        assertThat(entries["xl/workbook.xml"]).contains("name=\"Rover &lt;R2&gt;\"")
        val sheet = entries["xl/worksheets/sheet1.xml"]!!
        assertThat(
            sheet,
        ).contains(
            "<t xml:space=\"preserve\">Number</t>",
            "0001 &amp; co",
            "<c r=\"B2\"><v>12.5</v></c>",
            "autoFilter ref=\"A1:B3\"",
        )
        assertThat(Xlsx.column(0)).isEqualTo("A")
        assertThat(Xlsx.column(27)).isEqualTo("AB")
    }
}
