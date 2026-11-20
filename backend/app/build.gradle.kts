plugins {
    id("plm.spring-app")
}

dependencies {
    implementation(project(":backend:platform"))
    testImplementation(project(":backend:testing"))
    rootProject.subprojects
        .filter { it.path.startsWith(":backend:modules:") }
        .forEach { implementation(project(it.path)) }

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.springdoc.webmvc.ui)
    implementation(libs.flyway.postgresql)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.boot.starter.security.test)
}

// The committed API contract lives at the repository root: api/openapi.json (see OpenApiContractIT).
tasks.withType<Test>().configureEach {
    val contract = rootProject.file("api/openapi.json")
    systemProperty("plm.openapi.file", contract.absolutePath)
    // A FileCollection (unlike a single file input) may be missing on the very first run.
    inputs.files(contract).withPropertyName("openapiContract")
    val update = providers.systemProperty("plm.openapi.update").orNull
    if (update == "true") {
        systemProperty("plm.openapi.update", update)
        // Writes outside build/, so never reuse a cached or up-to-date result.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
