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
import com.plm.types.api.TypeProperties
import com.plm.types.api.TypeService
import com.plm.types.api.UnitOfMeasure
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.math.BigDecimal
import java.util.UUID

@PlmIntegrationTest
class AttributeModelExtrasIT(
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

    private fun subtype(parent: TypeId = TypeId.PART) =
        types.createSubtype(admin, CreateSubtype(parent, unique("T"), "T")).id

    private fun en(label: String) = mapOf("en" to label)

    private fun errors(e: Throwable): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val list = (e as ValidationException).details["errors"] as List<Map<String, String>>
        return list.associate { it.getValue("attribute") to it.getValue("code") }
    }

    @Test
    fun `type properties - translations, description, icon, abstract types`() {
        val type = subtype()
        val updated =
            types.update(
                admin,
                type,
                TypeProperties(
                    "Fastener",
                    "Screws, bolts and nuts",
                    mapOf(
                        "fr" to "Fixation",
                    ),
                    "bolt",
                    instantiable = false,
                ),
            )

        assertThat(types.get(alice, type)).isEqualTo(updated)
        assertThat(updated.labels).containsEntry("fr", "Fixation")
        assertThat(updated.instantiable).isFalse()
        assertThatThrownBy {
            types.update(
                admin,
                type,
                TypeProperties(
                    " ",
                    icon = "Not An Icon",
                    labels =
                        mapOf(
                            "french" to "x",
                        ),
                ),
            )
        }.isInstanceOfSatisfying(
            ValidationException::class.java,
        ) { assertThat(it.details["errors"] as List<*>).hasSize(3) }
        assertThatThrownBy {
            types.update(
                alice,
                type,
                TypeProperties("X"),
            )
        }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `admins add units to an existing dimension, or a new one starting with its base unit`() {
        val code = unique("u").take(8)
        enumerations.createUnit(
            admin,
            UnitOfMeasure(code, "LENGTH", en("My unit"), BigDecimal("0.0254"), BigDecimal.ZERO),
        )
        assertThat(enumerations.units(alice).single { it.code == code }.system).isFalse()

        val dimension =
            "TORQUE_" +
                UUID
                    .randomUUID()
                    .toString()
                    .take(4)
                    .uppercase()
                    .filter { it.isLetter() }
                    .ifEmpty { "X" }
        assertThatThrownBy {
            enumerations.createUnit(
                admin,
                UnitOfMeasure(unique("t").take(8), dimension, en("kN m"), BigDecimal(1000), BigDecimal.ZERO),
            )
        }.isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("INVALID_UNIT") }
        enumerations.createUnit(
            admin,
            UnitOfMeasure(unique("N").take(8), dimension, en("newton metre"), BigDecimal.ONE, BigDecimal.ZERO),
        )
        assertThatThrownBy {
            enumerations.createUnit(admin, UnitOfMeasure(code, "LENGTH", en("again"), BigDecimal.ONE, BigDecimal.ZERO))
        }.isInstanceOfSatisfying(ConflictException::class.java) { assertThat(it.code).isEqualTo("DUPLICATE_UNIT") }
        assertThatThrownBy {
            enumerations.createUnit(alice, UnitOfMeasure("x", "LENGTH", en("x"), BigDecimal.ONE, BigDecimal.ZERO))
        }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `a dependent value list only allows the values that go with the parent's value`() {
        val type = subtype()
        val standards =
            enumerations.create(
                admin,
                unique("std"),
                en("Standard"),
                listOf(EnumerationValue("metric", en("Metric")), EnumerationValue("unified", en("Unified"))),
            )
        val sizes =
            enumerations.create(
                admin,
                unique("size"),
                en("Size"),
                listOf(
                    EnumerationValue("M6", en("M6")),
                    EnumerationValue("M8", en("M8")),
                    EnumerationValue("UNC14", en("1/4-20 UNC")),
                ),
            )
        attributes.define(
            admin,
            DefineAttribute(
                type,
                "standard",
                DataType.ENUMERATION,
                enumerationId = standards.id,
                settings = AttributeSettings(en("Standard")),
            ),
        )
        attributes.define(
            admin,
            DefineAttribute(
                type,
                "size",
                DataType.ENUMERATION,
                enumerationId = sizes.id,
                settings =
                    AttributeSettings(
                        en("Size"),
                        dependsOn = "standard",
                        valueFilter =
                            mapOf(
                                "metric" to listOf("M6", "M8"),
                                "unified" to listOf("UNC14"),
                            ),
                    ),
            ),
        )

        assertThat(
            validator.validate(alice, type, mapOf("standard" to "metric", "size" to "M8"), null),
        ).containsEntry("size", "M8")
        assertThatThrownBy { validator.validate(alice, type, mapOf("standard" to "unified", "size" to "M8"), null) }
            .satisfies({ assertThat(errors(it)).containsEntry("size", "NOT_ALLOWED_FOR_PARENT") })
        // Without a parent value, any value is fine.
        assertThat(validator.validate(alice, type, mapOf("size" to "UNC14"), null)).containsEntry("size", "UNC14")

        assertThatThrownBy {
            attributes.define(
                admin,
                DefineAttribute(
                    type,
                    "bad",
                    DataType.ENUMERATION,
                    enumerationId = sizes.id,
                    settings =
                        AttributeSettings(
                            en("Bad"),
                            dependsOn = "standard",
                            valueFilter =
                                mapOf(
                                    "imperial" to listOf("M99"),
                                ),
                        ),
                ),
            )
        }.isInstanceOfSatisfying(
            ValidationException::class.java,
        ) { assertThat(it.details["errors"] as List<*>).hasSize(2) }
    }

    @Test
    fun `a global attribute is defined once and used by unrelated types`() {
        val part = subtype(TypeId.PART)
        val doc = subtype(TypeId.DOCUMENT)
        val child = subtype(part)
        val name =
            "export" +
                UUID
                    .randomUUID()
                    .toString()
                    .take(6)
                    .filter { it.isLetterOrDigit() }
        val global =
            attributes.defineGlobal(
                admin,
                DefineAttribute(TypeId.GLOBAL, name, DataType.STRING, settings = AttributeSettings(en("Export class"))),
            )

        attributes.attachGlobal(admin, global.id, part)
        attributes.attachGlobal(admin, global.id, doc)

        assertThat(
            attributes
                .effective(alice, child)
                .single {
                    it.name == name
                }.let { it.global && it.typeId == part },
        ).isTrue()
        assertThat(attributes.effective(alice, doc).map { it.name }).contains(name)
        assertThat(
            attributes
                .globals(alice)
                .single {
                    it.definition.id == global.id
                }.usedBy,
        ).containsExactlyInAnyOrder(part, doc)

        // One change applies everywhere.
        attributes.update(admin, global.id, AttributeSettings(en("Export class"), required = true))
        assertThatThrownBy {
            validator.validate(alice, doc, emptyMap(), null)
        }.satisfies({ assertThat(errors(it)).containsEntry(name, "REQUIRED") })

        // Names stay unique along a hierarchy.
        attributes.define(
            admin,
            DefineAttribute(
                subtype(TypeId.DOCUMENT).also {
                    attributes.attachGlobal(admin, global.id, it)
                },
                unique("own"),
                DataType.STRING,
                settings = AttributeSettings(en("Own")),
            ),
        )
        assertThatThrownBy {
            attributes.define(
                admin,
                DefineAttribute(child, name, DataType.STRING, settings = AttributeSettings(en("Clash"))),
            )
        }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { attributes.attachGlobal(admin, global.id, child) }
            .isInstanceOfSatisfying(
                ConflictException::class.java,
            ) { assertThat(it.code).isEqualTo("ALREADY_USED") }

        attributes.detachGlobal(admin, global.id, doc)
        assertThat(attributes.effective(alice, doc).map { it.name }).doesNotContain(name)
    }
}
