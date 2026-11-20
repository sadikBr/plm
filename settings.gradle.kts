pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "plm"

include(":backend:platform", ":backend:testing", ":backend:app")

// One Gradle project per domain module. See backend/modules/README.md.
val modules =
    listOf(
        "identity", "access", "types", "content", "versioning", "audit",
        "documents", "parts", "bom", "lifecycle", "workflow", "change",
        "search", "integration", "events",
    )
modules.forEach { include(":backend:modules:$it") }
