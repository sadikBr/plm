plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:bom"))
    implementation(project(":backend:modules:content"))
    implementation(libs.slf4j.api)
    implementation(libs.jackson.kotlin)

    testImplementation(project(":backend:modules:identity"))
}
