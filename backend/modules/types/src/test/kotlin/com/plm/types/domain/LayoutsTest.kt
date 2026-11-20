package com.plm.types.domain

import com.plm.types.api.AttributeDefinition
import com.plm.types.api.AttributeId
import com.plm.types.api.DataType
import com.plm.types.api.LayoutDefinition
import com.plm.types.api.LayoutGroup
import com.plm.types.api.LayoutScreen
import com.plm.types.api.LayoutSource
import com.plm.types.api.TypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class LayoutsTest {
    private val root = TypeId(UUID.randomUUID())
    private val child = TypeId(UUID.randomUUID())
    private val lineage = listOf(root, child)

    private fun def(
        name: String,
        type: TypeId = root,
        active: Boolean = true,
        required: Boolean = false,
        default: Any? = null,
    ) = AttributeDefinition(
        AttributeId(UUID.randomUUID()),
        type,
        name,
        mapOf("en" to name),
        null,
        DataType.STRING,
        false,
        required,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        default,
        0,
        active,
        false,
    )

    private val attributes = listOf(def("a"), def("b"), def("c"), def("old", active = false), def("x", type = child))

    private fun group(
        key: String,
        vararg names: String,
    ) = LayoutGroup(key, mapOf("en" to key), 2, names.toList())

    private fun layout(
        type: TypeId,
        screen: LayoutScreen,
        vararg groups: LayoutGroup,
        hidden: List<String> = emptyList(),
    ) = LayoutDefinition(type, screen, groups.toList(), hidden, Instant.EPOCH)

    @Test
    fun `without any layout every active attribute is in one generated group`() {
        val resolved = Layouts.resolve(child, LayoutScreen.CREATE, lineage, emptyList(), attributes)

        assertThat(resolved.source).isNull()
        assertThat(resolved.groups.map { it.attributes }).containsExactly(listOf("a", "b", "c", "x"))
    }

    @Test
    fun `attributes the layout does not place go to a trailing group, inactive and hidden ones are dropped`() {
        val saved = listOf(layout(root, LayoutScreen.DEFAULT, group("main", "c", "old", "a"), hidden = listOf("b")))

        val resolved = Layouts.resolve(child, LayoutScreen.INFO, lineage, saved, attributes)

        assertThat(resolved.source).isEqualTo(LayoutSource(root, LayoutScreen.DEFAULT))
        assertThat(resolved.groups.map { it.key to it.attributes })
            .containsExactly("main" to listOf("c", "a"), MORE_GROUP to listOf("x"))
    }

    @Test
    fun `the nearest type wins, and at each type its screen layout beats its default`() {
        val saved =
            listOf(
                layout(root, LayoutScreen.CREATE, group("rootCreate", "a")),
                layout(child, LayoutScreen.DEFAULT, group("childDefault", "b")),
                layout(child, LayoutScreen.INFO, group("childInfo", "c")),
            )

        fun source(screen: LayoutScreen) = Layouts.resolve(child, screen, lineage, saved, attributes).source

        assertThat(source(LayoutScreen.INFO)).isEqualTo(LayoutSource(child, LayoutScreen.INFO))
        assertThat(source(LayoutScreen.CREATE)).isEqualTo(LayoutSource(child, LayoutScreen.DEFAULT))
        assertThat(Layouts.resolve(root, LayoutScreen.CREATE, listOf(root), saved, attributes).source)
            .isEqualTo(LayoutSource(root, LayoutScreen.CREATE))
        assertThat(Layouts.resolve(root, LayoutScreen.EDIT, listOf(root), saved, attributes).source).isNull()
    }

    @Test
    fun `empty groups are left out`() {
        val saved = listOf(layout(root, LayoutScreen.DEFAULT, group("gone", "old"), group("main", "a", "b", "c", "x")))

        assertThat(Layouts.resolve(child, LayoutScreen.EDIT, lineage, saved, attributes).groups.map { it.key })
            .containsExactly("main")
    }

    @Test
    fun `a valid layout has no errors`() {
        assertThat(
            Layouts.errors(LayoutScreen.CREATE, listOf(group("main", "a", "b")), listOf("c"), attributes),
        ).isEmpty()
    }

    @Test
    fun `validation reports every problem`() {
        val errors =
            Layouts.errors(
                LayoutScreen.DEFAULT,
                listOf(
                    group("main", "a", "nope"),
                    group("main", "a"),
                    group(MORE_GROUP, "b"),
                    LayoutGroup("Bad-key", mapOf("fr" to "x"), 3, emptyList()),
                ),
                listOf("b", "ghost"),
                attributes,
            )

        assertThat(errors).containsExactlyInAnyOrder(
            "Group 'main': no attribute 'nope' on this type",
            "Group 'main' appears more than once",
            "Attribute 'a' is placed more than once",
            "Group 'more': 'more' is reserved for attributes the layout doesn't place",
            "Group 'Bad-key': key must be camelCase (letters and digits, starting lowercase)",
            "Group 'Bad-key': an English label (labels.en) is required",
            "Group 'Bad-key': columns must be 1 or 2",
            "Group 'Bad-key' has no attributes",
            "Attribute 'b' is both placed and hidden",
            "Hidden: no attribute 'ghost' on this type",
        )
    }

    @Test
    fun `required attributes without a default can be hidden from the info panel but not from forms`() {
        val defs = listOf(def("must", required = true), def("defaulted", required = true, default = "x"), def("a"))
        val hidden = listOf("must", "defaulted")

        assertThat(Layouts.errors(LayoutScreen.INFO, listOf(group("main", "a")), hidden, defs)).isEmpty()
        assertThat(Layouts.errors(LayoutScreen.CREATE, listOf(group("main", "a")), hidden, defs))
            .containsExactly("Attribute 'must' is required and has no default, so it can't be hidden from forms")
    }
}
