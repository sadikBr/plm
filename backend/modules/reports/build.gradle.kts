plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:search"))
    implementation(project(":backend:modules:query"))
    implementation(project(":backend:modules:jobs"))
    implementation(project(":backend:modules:notifications"))
    implementation(libs.slf4j.api)
    implementation(libs.jackson.kotlin)

    testImplementation(project(":backend:modules:identity"))
}
