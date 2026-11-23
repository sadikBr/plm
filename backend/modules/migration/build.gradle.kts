plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:lifecycle"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:content"))
    implementation(project(":backend:modules:bom"))
    implementation(project(":backend:modules:jobs"))
    implementation(libs.jackson.kotlin)
}
