plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:lifecycle"))
    implementation(project(":backend:modules:identity"))
}
