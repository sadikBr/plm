// Conventions for a domain module (backend/modules/*) and the platform kernel.
plugins {
    id("plm.kotlin")
    id("org.jetbrains.kotlin.plugin.spring")
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    if (path != ":backend:platform") {
        "api"(project(":backend:platform"))
    }
    "implementation"(libs.findBundle("module-runtime").get())
    "testImplementation"(project(":backend:testing"))
}
