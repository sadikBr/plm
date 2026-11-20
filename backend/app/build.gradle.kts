plugins {
    id("plm.spring-app")
}

dependencies {
    implementation(project(":backend:platform"))
    rootProject.subprojects
        .filter { it.path.startsWith(":backend:modules:") }
        .forEach { implementation(project(it.path)) }

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.archunit.junit5)
}
