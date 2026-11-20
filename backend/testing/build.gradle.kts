// Shared test support: a Postgres-backed Spring context for module integration tests.
plugins {
    id("plm.kotlin")
    `java-library`
}

dependencies {
    api(project(":backend:platform"))
    api(libs.spring.boot.starter.test)
    api(libs.spring.boot.testcontainers)
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.junit)
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.jackson.kotlin)
    runtimeOnly(libs.postgresql)
}
