plugins {
    id("plm.module")
}

dependencies {
    // What the report builder can ask about these objects (query module).
    implementation(project(":backend:modules:query"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(libs.jackson.kotlin)
}
