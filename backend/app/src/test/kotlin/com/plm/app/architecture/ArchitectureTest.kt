package com.plm.app.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private val production =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.plm")

    @Test
    fun `module internals are only used by their own module`() {
        ModuleBoundaryRules.internalsArePrivate("com.plm").check(production)
    }

    @Test
    fun `modules have no package cycles`() {
        ModuleBoundaryRules.modulesAreFreeOfCycles("com.plm").check(production)
    }

    @Test
    fun `boundary rule detects a violation`() {
        // Guards against a rule that silently passes: the fixture has beta using alpha.domain.
        val fixture = ClassFileImporter().importPackages("com.plm.archfixture")
        val result = ModuleBoundaryRules.internalsArePrivate("com.plm.archfixture").evaluate(fixture)

        assertThat(result.hasViolation()).isTrue()
        assertThat(result.failureReport.details).anyMatch { it.contains("BetaService") }
    }
}
