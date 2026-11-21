plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:content"))
    implementation(project(":backend:modules:jobs"))
    implementation(libs.slf4j.api)
    implementation(libs.jackson.kotlin)
    implementation(libs.pdfbox)
}
