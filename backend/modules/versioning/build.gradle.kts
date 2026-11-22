plugins {
    id("plm.module")
}

dependencies {
    // What the report builder can ask about these objects (query module).
    implementation(project(":backend:modules:query"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:lifecycle"))
    implementation(project(":backend:modules:preferences"))
    implementation(libs.slf4j.api)
}
