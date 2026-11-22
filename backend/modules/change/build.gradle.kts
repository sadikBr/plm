plugins {
    id("plm.module")
}

dependencies {
    // What the report builder can ask about these objects (query module).
    implementation(project(":backend:modules:query"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:lifecycle"))
    implementation(project(":backend:modules:workflow"))
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:notifications"))
    implementation(project(":backend:modules:bom"))
    implementation(libs.slf4j.api)
    implementation(libs.jackson.databind)

    testImplementation(project(":backend:modules:identity"))
}
