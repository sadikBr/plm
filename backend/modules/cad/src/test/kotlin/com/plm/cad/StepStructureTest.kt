package com.plm.cad

import com.plm.cad.domain.StepStructure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Reading a STEP file's product structure (roadmap 6.5). */
internal class StepStructureTest {
    private val as1 = StepStructure.read(javaClass.getResource("/step/as1_pe_203.stp")!!.readText())

    @Test
    fun `reads the AS1 assembly`() {
        val names = as1.products.mapValues { it.value.name }
        println(names.values.sorted())
        println(
            as1.usages.map {
                "${names[it.parent]} > ${names[it.child]} ${it.occurrence} ${it.transform.subList(
                    12,
                    15,
                )}"
            },
        )
        assertThat(as1.roots.map { names[it] }).containsExactly("AS1_PE_ASM")
        assertThat(as1.unitToMm).isEqualTo(1.0)
        val top = as1.roots.single()
        assertThat(as1.children(top).map { names[it.child] }).contains("PLATE", "L_BRACKET_ASSEMBLY_ASM", "ROD_ASM")
        assertThat(as1.children(top).count { names[it.child] == "L_BRACKET_ASSEMBLY_ASM" }).isEqualTo(2)
    }
}
