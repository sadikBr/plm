plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:jobs"))

    implementation(libs.jackson.kotlin)

    testImplementation(project(":backend:modules:types"))
}
