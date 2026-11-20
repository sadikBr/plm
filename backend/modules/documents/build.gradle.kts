plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:content"))
    implementation(project(":backend:modules:lifecycle"))
}
