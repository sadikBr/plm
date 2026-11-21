plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:jobs"))
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.slf4j.api)

    implementation(project(":backend:modules:types"))
}
