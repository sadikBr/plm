plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:access"))
    implementation(libs.jackson.kotlin)
}
