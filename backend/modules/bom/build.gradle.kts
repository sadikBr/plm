plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:preferences"))
    implementation(libs.slf4j.api)

    testImplementation(project(":backend:modules:identity"))
}
