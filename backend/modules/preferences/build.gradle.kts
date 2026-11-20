plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
}
