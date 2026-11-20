package com.plm.app.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * Architecture rules from ADR 0001.
 *
 * A module is the first package segment below [root] (e.g. `com.plm.documents`).
 * Its `domain` and `infra` packages are private to it; other modules may only use `api`.
 */
object ModuleBoundaryRules {
    fun internalsArePrivate(root: String): ArchRule =
        classes()
            .that()
            .resideInAnyPackage("$root.*.domain..", "$root.*.infra..")
            .should(onlyBeAccessedFromOwnModule(root))
            .allowEmptyShould(true)

    fun modulesAreFreeOfCycles(root: String): ArchRule =
        slices()
            .matching("$root.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)

    private fun moduleOf(
        root: String,
        packageName: String,
    ): String = packageName.removePrefix("$root.").substringBefore('.')

    private fun onlyBeAccessedFromOwnModule(root: String) =
        object : ArchCondition<JavaClass>("only be accessed from their own module") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                val owner = moduleOf(root, item.packageName)
                item.directDependenciesToSelf
                    .filter { moduleOf(root, it.originClass.packageName) != owner }
                    .forEach { events.add(SimpleConditionEvent.violated(it, it.description)) }
            }
        }
}
