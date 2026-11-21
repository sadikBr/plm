plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:lifecycle"))
    implementation(project(":backend:modules:workflow"))
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:notifications"))
    implementation(libs.slf4j.api)

    testImplementation(project(":backend:modules:identity"))
}
