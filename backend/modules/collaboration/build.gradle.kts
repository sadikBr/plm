plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:notifications"))
}

dependencies {
    testImplementation(project(":backend:modules:audit"))
}
