plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:lifecycle"))
    implementation(project(":backend:modules:access"))
}
