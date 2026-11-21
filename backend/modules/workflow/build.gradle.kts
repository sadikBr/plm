plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:lifecycle"))
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:notifications"))
    implementation(libs.jackson.kotlin)
    implementation(libs.slf4j.api)
    implementation(libs.temporal.sdk)
    // The in-process Temporal server: tests, and running without a Temporal service (plm.workflow.temporal.target=in-memory).
    implementation(libs.temporal.testing)
}
