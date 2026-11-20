plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:lifecycle"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:configuration"))
    implementation(libs.slf4j.api)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.kotlin)

    testImplementation(project(":backend:modules:identity"))
}
