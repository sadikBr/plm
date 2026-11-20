package com.plm.types

import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.AttributeService
import com.plm.types.api.AttributeSettings
import com.plm.types.api.AttributeValidator
import com.plm.types.api.CreateSubtype
import com.plm.types.api.DataType
import com.plm.types.api.DefineAttribute
import com.plm.types.api.EnumerationService
import com.plm.types.api.EnumerationValue
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

@PlmIntegrationTest
class AttributeServiceIT(
    @Autowired private val types: TypeService,
    @Autowired private val attributes: AttributeService,
    @Autowired private val validator: AttributeValidator,
    @Autowired private val enumerations: EnumerationService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    private fun unique(prefix: String) =
        prefix +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)

    private fun en(label: String) = mapOf("en" to label)

    private fun errorCodes(e: Throwable): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val errors = (e as ValidationException).details["errors"] as List<Map<String, String>>
        return errors.associate { it.getValue("attribute") to it.getValue("code") }
    }

    @Test
    fun `parts and documents come with their out-of-the-box attributes`() {
        val part = attributes.effective(alice, TypeId.PART)
        val document = attributes.effective(alice, TypeId.DOCUMENT)

        assertThat(part.map { it.name }).containsExactly(
            "description",
            "source",
            "defaultUnit",
            "assemblyMode",
            "endItem",
            "phantom",
            "gatheringPart",
            "traceCode",
            "material",
            "mass",
        )
        assertThat(part).allMatch { it.system }
        assertThat(document.map { it.name }).containsExactly("title", "description", "category", "department")
        assertThat(part.single { it.name == "mass" }.dimension).isEqualTo("MASS")
    }

    @Test
    fun `a new part gets the out-of-the-box defaults`() {
        val values = validator.validate(alice, TypeId.PART, mapOf("material" to "AL6061"), null)

        assertThat(values).isEqualTo(
            mapOf(
                "source" to "make",
                "defaultUnit" to "each",
                "assemblyMode" to "separable",
                "endItem" to false,
                "phantom" to false,
                "gatheringPart" to false,
                "traceCode" to "untraced",
                "material" to "AL6061",
            ),
        )
    }

    @Test
    fun `subtypes inherit attributes and add their own`() {
        val fastener = types.createSubtype(admin, CreateSubtype(TypeId.PART, unique("Fastener"), "Fastener"))
        val thread =
            attributes.define(
                admin,
                DefineAttribute(
                    fastener.id,
                    "threadSize",
                    DataType.STRING,
                    settings = AttributeSettings(en("Thread size"), required = true, pattern = "M\\d+(x[\\d.]+)?"),
                ),
            )

        val effective = attributes.effective(alice, fastener.id)
        assertThat(effective.map { it.name }.last()).isEqualTo("threadSize")
        assertThat(effective.first { it.name == "source" }.typeId).isEqualTo(TypeId.PART)
        assertThat(attributes.effective(alice, TypeId.PART).map { it.id }).doesNotContain(thread.id)

        assertThatThrownBy { validator.validate(alice, fastener.id, mapOf("threadSize" to "1/4-20"), null) }
            .satisfies({ assertThat(errorCodes(it)).isEqualTo(mapOf("threadSize" to "PATTERN_MISMATCH")) })
        assertThatThrownBy { validator.validate(alice, fastener.id, emptyMap(), null) }
            .satisfies({ assertThat(errorCodes(it)).isEqualTo(mapOf("threadSize" to "REQUIRED")) })
        assertThat(
            validator.validate(alice, fastener.id, mapOf("threadSize" to "M6x1.0"), null),
        ).containsEntry("threadSize", "M6x1.0")
    }

    @Test
    fun `names are unique along the whole type hierarchy`() {
        val bracket = types.createSubtype(admin, CreateSubtype(TypeId.PART, unique("Bracket"), "Bracket"))
        val attr = unique("finish").lowercase()
        attributes.define(
            admin,
            DefineAttribute(bracket.id, attr, DataType.STRING, settings = AttributeSettings(en("Finish"))),
        )

        // A parent can't add a name a subtype already uses, and a subtype can't shadow an inherited one.
        assertThatThrownBy {
            attributes.define(
                admin,
                DefineAttribute(TypeId.PART, attr, DataType.STRING, settings = AttributeSettings(en("X"))),
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("already exists")
        assertThatThrownBy {
            attributes.define(
                admin,
                DefineAttribute(bracket.id, "mass", DataType.REAL, settings = AttributeSettings(en("Mass"))),
            )
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `definitions are checked for consistency`() {
        val bad =
            listOf(
                DefineAttribute(TypeId.PART, "Bad Name", DataType.STRING, settings = AttributeSettings(en("x"))),
                DefineAttribute(
                    TypeId.PART,
                    unique("q"),
                    DataType.QUANTITY,
                    dimension = "SMELL",
                    settings = AttributeSettings(en("x")),
                ),
                DefineAttribute(TypeId.PART, unique("e"), DataType.ENUMERATION, settings = AttributeSettings(en("x"))),
                DefineAttribute(
                    TypeId.PART,
                    unique("n"),
                    DataType.INTEGER,
                    settings = AttributeSettings(en("x"), pattern = "\\d+"),
                ),
                DefineAttribute(
                    TypeId.PART,
                    unique("n"),
                    DataType.INTEGER,
                    settings = AttributeSettings(en("x"), defaultValue = "seven"),
                ),
                DefineAttribute(TypeId.PART, unique("s"), DataType.STRING, settings = AttributeSettings(emptyMap())),
                DefineAttribute(
                    TypeId.PART,
                    unique("r"),
                    DataType.REAL,
                    settings = AttributeSettings(en("x"), minValue = BigDecimal.TEN, maxValue = BigDecimal.ONE),
                ),
            )

        bad.forEach { command ->
            assertThatThrownBy { attributes.define(admin, command) }
                .describedAs(command.toString())
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo("INVALID_ATTRIBUTE_DEFINITION")
                }
        }
    }

    @Test
    fun `only site administrators change definitions`() {
        assertThatThrownBy {
            attributes.define(
                alice,
                DefineAttribute(TypeId.PART, unique("x"), DataType.STRING, settings = AttributeSettings(en("x"))),
            )
        }.isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ADMIN_REQUIRED") }
    }

    @Test
    fun `out-of-the-box attributes can be constrained and deactivated`() {
        val type = types.createSubtype(admin, CreateSubtype(TypeId.DOCUMENT, unique("Spec"), "Spec"))
        val ref =
            attributes.define(
                admin,
                DefineAttribute(
                    type.id,
                    unique("ref").lowercase(),
                    DataType.STRING,
                    settings = AttributeSettings(en("Reference")),
                ),
            )
        val stored = validator.validate(alice, type.id, mapOf(ref.name to "R-1"), null)

        val updated =
            attributes.update(
                admin,
                ref.id,
                AttributeSettings(en("Reference"), required = true, active = false),
            )
        assertThat(updated.active).isFalse()
        assertThat(attributes.effective(alice, type.id).map { it.name }).doesNotContain(ref.name)
        assertThat(attributes.effective(alice, type.id, includeInactive = true).map { it.name }).contains(ref.name)

        // Existing values survive; nothing new can be written.
        assertThat(validator.validate(alice, type.id, emptyMap(), stored)).containsEntry(ref.name, "R-1")
        assertThatThrownBy { validator.validate(alice, type.id, mapOf(ref.name to "R-2"), stored) }
            .satisfies({ assertThat(errorCodes(it)).isEqualTo(mapOf(ref.name to "INACTIVE_ATTRIBUTE")) })
    }

    @Test
    fun `enumerations can grow and deactivate values, never drop them`() {
        val e =
            enumerations.create(
                admin,
                unique("coating"),
                en("Coating"),
                listOf(EnumerationValue("zinc", en("Zinc")), EnumerationValue("nickel", en("Nickel"))),
            )

        val updated =
            enumerations.update(
                admin,
                e.id,
                en("Coating"),
                listOf(
                    EnumerationValue("nickel", en("Nickel"), active = false),
                    EnumerationValue("zinc", en("Zinc")),
                    EnumerationValue("black", en("Black oxide")),
                ),
            )
        assertThat(updated.values.map { it.key }).containsExactly("nickel", "zinc", "black")
        assertThat(
            enumerations
                .get(alice, e.id)
                .values
                .single { it.key == "nickel" }
                .active,
        ).isFalse()

        assertThatThrownBy {
            enumerations.update(
                admin,
                e.id,
                en("Coating"),
                listOf(EnumerationValue("zinc", en("Zinc"))),
            )
        }.isInstanceOfSatisfying(
            ValidationException::class.java,
        ) { assertThat(it.code).isEqualTo("ENUMERATION_VALUE_REMOVED") }
        assertThatThrownBy { enumerations.create(admin, e.name, en("Again"), listOf(EnumerationValue("a", en("A")))) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `units cover the common engineering dimensions`() {
        val units = enumerations.units(alice)

        assertThat(units.map { it.dimension }.toSet()).contains("LENGTH", "MASS", "TEMPERATURE", "PRESSURE", "TORQUE")
        assertThat(units.single { it.code == "in" }.factor).isEqualByComparingTo("0.0254")
    }
}
