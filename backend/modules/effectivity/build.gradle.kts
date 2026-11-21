plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:bom"))
}

dependencies {
    // Pending effectivity is set through change notices (roadmap 7.3).
    testImplementation(project(":backend:modules:change"))
}
