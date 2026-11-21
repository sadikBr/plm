plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:lifecycle"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:configuration"))
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:preferences"))
    implementation(project(":backend:modules:jobs"))
    implementation(libs.slf4j.api)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.kotlin)
}
